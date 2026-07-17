package eu.kanade.tachiyomi.animeextension.en.anidb

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Serializable
data class EpisodeListDto(
    val episodes: List<EpisodeDto>,
)

@Serializable
data class EpisodeDto(
    val id: Long,
    val number: Float,
    val number2: Float? = null,
    val filler: Boolean = false,
)

@Serializable
data class LanguageListDto(
    val languages: List<LanguageDto>,
)

@Serializable
data class LanguageDto(
    val code: String,
    val name: String,
    val embed_url: String,
)

@Serializable
data class AnilistMalIdResponse(
    val data: DataObject,
) {
    @Serializable
    class DataObject(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            @SerialName("idMal") val idMal: Int? = null,
        )
    }
}

@Serializable
data class JikanEpisodesDto(
    val data: List<JikanEpisodeDataDto>,
    val pagination: JikanPaginationDto,
) {
    @Serializable
    class JikanEpisodeDataDto(
        @SerialName("mal_id") val number: Int,
        val filler: Boolean,
    )

    @Serializable
    class JikanPaginationDto(
        @SerialName("has_next_page") val hasNextPage: Boolean,
    )
}

class AniDB :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AniDB"

    override val baseUrl = "https://anidb.app"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AniDBCloudflareInterceptor(network.client) { baseUrl })
        .build()

    private val jikanClient: OkHttpClient = network.client.newBuilder()
        .rateLimitHost(JIKAN_API_URL.toHttpUrl(), permits = 1, period = 1, unit = TimeUnit.SECONDS)
        .build()

    private val metaClient: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val cfBypassUserAgent by lazy {
        preferences.getString(PREF_CF_UA_KEY, PREF_CF_UA_DEFAULT)
            ?.takeIf { it.isNotBlank() } ?: PREF_CF_UA_DEFAULT
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", cfBypassUserAgent)

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private val m3u8Regex = Regex("""file:\s*['"](https?://[^'"]+master\.m3u8)['"]""")

    // ============================== Preferences ===========================

    private val SharedPreferences.preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.preferredLang by preferences.delegate(PREF_LANG_KEY, PREF_LANG_DEFAULT)
    private val SharedPreferences.preferredSub by preferences.delegate(PREF_SUB_KEY, PREF_SUB_DEFAULT)
    private val SharedPreferences.preferredTitleStyle by preferences.delegate(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)
    private val SharedPreferences.markFillers by preferences.delegate(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT)
    private val SharedPreferences.hideFillers by preferences.delegate(PREF_HIDE_FILLERS_KEY, PREF_HIDE_FILLERS_DEFAULT)
    private val SharedPreferences.stripHtml by preferences.delegate(PREF_STRIP_HTML_KEY, PREF_STRIP_HTML_DEFAULT)
    private val SharedPreferences.episodeMeta by preferences.delegate(PREF_EP_META_KEY, PREF_EP_META_DEFAULT)
    private val SharedPreferences.useAnilistEpTitles by preferences.delegate(PREF_ANILIST_EP_TITLES_KEY, PREF_ANILIST_EP_TITLES_DEFAULT)

    // ============================== Metadata Cache ========================

    private data class EpisodeMeta(
        var title: String? = null,
        var thumbnail: String? = null,
        var overview: String? = null,
    )

    private data class AnimeMeta(
        val anilistId: Int? = null,
        var malId: Int? = null,
        var fillerEpisodes: Set<Float>? = null,
        var episodeMeta: Map<Float, EpisodeMeta>? = null,
        var coverImage: String? = null,
        var anilistFetched: Boolean = false,
    )

    @Volatile
    private var cachedAnimeMeta: AnimeMeta? = null

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?sort=order_popular&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse?sort=order_updated&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("type", filter.toUriPart())
                is Filters.StatusFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())
                is Filters.SeasonFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())
                is Filters.YearFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("year", filter.toUriPart())
                is Filters.GenreFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("genres", filter.toUriPart())
                is Filters.SortFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())
                else -> {}
            }
        }
        urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.YearFilter(),
        Filters.GenreFilter(),
        Filters.SortFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val titleStyle = preferences.preferredTitleStyle

        return SAnime.create().apply {
            title = document.selectFirst("h1")?.text() ?: ""
            thumbnail_url = document.selectFirst("img[src*=posters]")?.attr("abs:src")
            description = if (preferences.stripHtml) {
                document.select("h2:contains(Synopsis) + div p").text()
                    .replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
                    .replace("</p>".toRegex(RegexOption.IGNORE_CASE), "\n")
                    .replace("<[^>]+>".toRegex(), "")
                    .trim()
            } else {
                document.select("h2:contains(Synopsis) + div p").text()
            }
            author = document.select("dt:contains(Studios) + dd a").text()
            val statusText = document.select("dt:contains(Status) + dd a").text()
            status = when {
                statusText.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
                statusText.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            val genresList = document.select("dt:contains(Themes) + dd a, a[href*=/genres/]").map { it.text() }
            genre = genresList.distinct().joinToString()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.trimEnd('/').substringAfterLast("-")
        return GET("$baseUrl/api/frontend/anime/$animeId/episodes", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<EpisodeListDto>()
        val minEpNumber = data.episodes.minOfOrNull { it.number } ?: 0f
        val offset = if (minEpNumber > 1f) minEpNumber - 1f else 0f

        // Try to extract AniDB anime ID for AniList lookup
        val animeUrl = response.request.url.toString()
        val anidbId = animeUrl.substringAfter("/anime/").substringBefore("/episodes").toIntOrNull()

        // Resolve filler episodes if enabled
        val fillerEpisodes = if (preferences.markFillers || preferences.hideFillers) {
            resolveFillerEpisodes(anidbId, data.episodes)
        } else {
            emptySet()
        }

        // Resolve AniList episode metadata if enabled
        val episodeMetaMap = if (preferences.episodeMeta || preferences.useAnilistEpTitles) {
            resolveAnilistMeta(anidbId).episodeMeta
        } else {
            null
        }

        val episodes = data.episodes.map { ep ->
            SEpisode.create().apply {
                val adjustedNumber = ep.number - offset
                val adjustedNumber2 = ep.number2?.let { it - offset }
                val label = if (adjustedNumber2 != null && adjustedNumber2 != 0f && adjustedNumber2 != adjustedNumber) {
                    "${adjustedNumber.toInt()}–${adjustedNumber2.toInt()}"
                } else {
                    adjustedNumber.toInt().toString()
                }

                // Apply AniList episode title if available
                val epMeta = episodeMetaMap?.get(adjustedNumber)
                val anilistTitle = epMeta?.title
                name = if (anilistTitle != null && anilistTitle.isNotBlank()) {
                    "Episode $label: $anilistTitle"
                } else {
                    "Episode $label"
                }

                if (ep.filler || fillerEpisodes.contains(adjustedNumber)) {
                    name += " (Filler)"
                }

                episode_number = adjustedNumber
                url = ep.id.toString()

                // Set episode extras (thumbnail + description) if supported
                if (preferences.episodeMeta) {
                    trySetEpisodeExtras(this, epMeta?.overview, epMeta?.thumbnail)
                }
            }
        }.let { list ->
            if (preferences.hideFillers) {
                list.filter { !it.name.contains("Filler") }
            } else {
                list
            }
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val languagesResponse = client.newCall(
            GET("$baseUrl/api/frontend/episode/${episode.url}/languages", headers),
        ).awaitSuccess()
        val data = languagesResponse.parseAs<LanguageListDto>()

        return data.languages.parallelCatchingFlatMapBlocking { lang ->
            val embedResponse = client.newCall(GET(lang.embed_url, headers)).execute()
            val html = embedResponse.body.string()
            val m3u8Url = m3u8Regex.find(html)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "$baseUrl/",
                masterHeaders = headers,
                videoHeaders = headers,
                videoNameGen = { quality: String -> "${lang.name} - $quality" },
            )
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.preferredQuality
        val lang = preferences.preferredLang

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                {
                    if (lang == "eng") {
                        it.quality.contains("English", ignoreCase = true)
                    } else {
                        it.quality.contains("Japanese", ignoreCase = true)
                    }
                },
            ),
        ).reversed()
    }

    // ============================== Filler ================================

    private fun resolveFillerEpisodes(anidbId: Int?, episodes: List<EpisodeDto>): Set<Float> {
        if (anidbId == null) return emptySet()

        val meta = cachedAnimeMeta
        if (meta != null && meta.anilistId == anidbId && meta.fillerEpisodes != null) {
            return meta.fillerEpisodes!!
        }

        // Try to resolve MAL ID via AniList
        val anilistId = meta?.anilistId ?: anidbId
        val malId = meta?.malId ?: fetchMalId(anilistId)
        if (malId == null) {
            cachedAnimeMeta = AnimeMeta(anilistId = anidbId, malId = null, fillerEpisodes = emptySet())
            return emptySet()
        }

        val maxEp = episodes.maxOfOrNull { it.number } ?: Float.MAX_VALUE
        val fillers = fetchFillerEpisodes(malId, maxEp)

        cachedAnimeMeta = AnimeMeta(anilistId = anidbId, malId = malId, fillerEpisodes = fillers)
        return fillers
    }

    private fun fetchMalId(anilistId: Int): Int? = try {
        val query = """
            query media(${'$'}id: Int, ${'$'}type: MediaType) {
                Media(id: ${'$'}id, type: ${'$'}type) { idMal }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        val body = FormBody.Builder()
            .add("query", query)
            .add("variables", kotlinx.serialization.json.Json.encodeToString(variables))
            .build()
        metaClient.newCall(POST(ANILIST_GRAPHQL_URL, body = body)).execute()
            .parseAs<AnilistMalIdResponse>().data.media.idMal
    } catch (e: Exception) {
        Log.e("AniDB", "Failed to resolve MAL ID: ${e.message}")
        null
    }

    private fun fetchFillerEpisodes(malId: Int, maxEpisode: Float = Float.MAX_VALUE): Set<Float> {
        val fillerEpisodes = mutableSetOf<Float>()
        var page = 1
        var hasNextPage = true
        val maxPages = 10

        while (hasNextPage && page <= maxPages) {
            val result = try {
                jikanClient.newCall(
                    GET("$JIKAN_API_URL/anime/$malId/episodes?page=$page"),
                ).execute().parseAs<JikanEpisodesDto>()
            } catch (e: Exception) {
                Log.e("AniDB", "Failed to fetch Jikan episodes: ${e.message}")
                break
            }

            for (ep in result.data) {
                val num = ep.number.toFloat()
                if (num > maxEpisode) {
                    hasNextPage = false
                    break
                }
                if (ep.filler) fillerEpisodes.add(num)
            }

            if (hasNextPage) {
                hasNextPage = result.pagination.hasNextPage
                page++
            }
        }

        return fillerEpisodes
    }

    // ============================ AniList Meta ============================

    private fun resolveAnilistMeta(anilistId: Int?): AnimeMeta {
        if (anilistId == null) return AnimeMeta()

        val cached = cachedAnimeMeta?.takeIf { it.anilistId == anilistId && it.anilistFetched }
        if (cached != null) return cached

        val meta = cachedAnimeMeta?.takeIf { it.anilistId == anilistId }
            ?: AnimeMeta(anilistId = anilistId).also { cachedAnimeMeta = it }

        val episodeMeta = mutableMapOf<Float, EpisodeMeta>()

        // Fetch from ani.zip
        try {
            val json = fetchJsonWithRetry(
                GET("$ANIZIP_API_URL?anilist_id=$anilistId"),
                "ani.zip",
            )
            val episodesObj = json?.optJSONObject("episodes")
            if (episodesObj != null) {
                for (key in episodesObj.keys()) {
                    val number = key.toFloatOrNull() ?: continue
                    val ep = episodesObj.optJSONObject(key) ?: continue

                    val titleObj = ep.optJSONObject("title")
                    val title = titleObj?.optString("en").orEmpty()
                        .ifEmpty { titleObj?.optString("x-jat").orEmpty() }
                        .ifEmpty { titleObj?.optString("ja").orEmpty() }
                        .takeIf { it.isNotBlank() && !it.startsWith("Episode ", ignoreCase = true) }

                    val thumbnail = ep.optString("image").takeIf(String::isNotBlank)
                    val overview = ep.optString("overview").takeIf(String::isNotBlank)

                    if (title != null || thumbnail != null || overview != null) {
                        episodeMeta[number] = EpisodeMeta(title, thumbnail, overview)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AniDB", "Failed to fetch ani.zip metadata: ${e.message}")
        }

        // Fetch from AniList
        try {
            val query = """
                query media(${'$'}id: Int, ${'$'}type: MediaType) {
                    Media(id: ${'$'}id, type: ${'$'}type) {
                        idMal
                        streamingEpisodes { title thumbnail }
                    }
                }
            """.trimIndent()
            val variables = buildJsonObject {
                put("id", anilistId)
                put("type", "ANIME")
            }
            val body = FormBody.Builder()
                .add("query", query)
                .add("variables", kotlinx.serialization.json.Json.encodeToString(variables))
                .build()

            val json = fetchJsonWithRetry(POST(ANILIST_GRAPHQL_URL, body = body), "AniList")
            val media = json?.optJSONObject("data")?.optJSONObject("Media")
            if (media != null) {
                media.optInt("idMal", 0).takeIf { it > 0 }?.let { meta.malId = it }

                val streamingEpisodes = media.optJSONArray("streamingEpisodes")
                if (streamingEpisodes != null) {
                    for (i in 0 until streamingEpisodes.length()) {
                        val epObj = streamingEpisodes.optJSONObject(i) ?: continue
                        val rawTitle = epObj.optString("title").orEmpty()
                        val match = EPISODE_TITLE_REGEX.find(rawTitle) ?: continue
                        val number = match.groupValues[1].toFloatOrNull() ?: continue
                        val title = match.groupValues[2].trim()
                        val thumbnail = epObj.optString("thumbnail").takeIf(String::isNotBlank)

                        val existing = episodeMeta.getOrPut(number) { EpisodeMeta() }
                        if (existing.title.isNullOrEmpty() && title.isNotEmpty()) existing.title = title
                        if (existing.thumbnail.isNullOrEmpty()) existing.thumbnail = thumbnail
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AniDB", "Failed to fetch AniList metadata: ${e.message}")
        }

        meta.episodeMeta = episodeMeta
        meta.anilistFetched = true
        return meta
    }

    private fun fetchJsonWithRetry(request: Request, tag: String, attempts: Int = 3): JSONObject? {
        repeat(attempts) { attempt ->
            try {
                metaClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return JSONObject(resp.body.string())
                    }
                    Log.e("AniDB", "$tag request failed: HTTP ${resp.code} (attempt ${attempt + 1}/$attempts)")
                    if (resp.code in 400..499) return null
                }
            } catch (e: Exception) {
                Log.e("AniDB", "$tag request error (attempt ${attempt + 1}/$attempts): ${e.message}")
            }
            if (attempt < attempts - 1) Thread.sleep(1000)
        }
        return null
    }

    // ============================ Episode Extras ==========================

    private val episodeExtraSetters by lazy {
        val clazz = SEpisode.create().javaClass
        val summary = runCatching { clazz.getMethod("setSummary", String::class.java) }.getOrNull()
        val preview = runCatching { clazz.getMethod("setPreview_url", String::class.java) }.getOrNull()
        summary to preview
    }

    private fun trySetEpisodeExtras(episode: SEpisode, summary: String?, previewUrl: String?) {
        val (summarySetter, previewSetter) = episodeExtraSetters
        try {
            if (summary != null) summarySetter?.invoke(episode, summary)
            if (previewUrl != null) previewSetter?.invoke(episode, previewUrl)
        } catch (e: Exception) {
            Log.e("AniDB", "Failed to set episode extras: ${e.message}")
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = PREF_LANG_TITLE,
            entries = PREF_LANG_ENTRIES,
            entryValues = PREF_LANG_VALUES,
            default = PREF_LANG_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SUB_KEY,
            title = "Preferred Sub/Dub",
            entries = PREF_SUB_ENTRIES,
            entryValues = PREF_SUB_VALUES,
            default = PREF_SUB_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TITLE_STYLE_KEY,
            title = PREF_TITLE_STYLE_TITLE,
            entries = PREF_TITLE_STYLE_ENTRIES,
            entryValues = PREF_TITLE_STYLE_VALUES,
            default = PREF_TITLE_STYLE_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_MARK_FILLERS_KEY,
            title = "Mark filler episodes",
            default = PREF_MARK_FILLERS_DEFAULT,
            summary = "Requires fetching episode data from Jikan/MAL, which may take some time.",
        )
        screen.addSwitchPreference(
            key = PREF_HIDE_FILLERS_KEY,
            title = "Hide filler episodes",
            default = PREF_HIDE_FILLERS_DEFAULT,
            summary = "Hides filler episodes from the episode list.",
        )
        screen.addSwitchPreference(
            key = PREF_EP_META_KEY,
            title = "Episode Metadata",
            default = PREF_EP_META_DEFAULT,
            summary = "Show episode thumbnails and descriptions from AniList/ani.zip.",
        )
        screen.addSwitchPreference(
            key = PREF_ANILIST_EP_TITLES_KEY,
            title = "AniList Episode Titles",
            default = PREF_ANILIST_EP_TITLES_DEFAULT,
            summary = "Use official episode titles from AniList when available.",
        )
        screen.addSwitchPreference(
            key = PREF_STRIP_HTML_KEY,
            title = "Strip HTML from descriptions",
            default = PREF_STRIP_HTML_DEFAULT,
            summary = "Strip HTML tags from anime descriptions.",
        )
        screen.addEditTextPreference(
            key = PREF_CF_UA_KEY,
            title = PREF_CF_UA_TITLE,
            summary = PREF_CF_UA_SUMMARY,
            default = PREF_CF_UA_DEFAULT,
        )
    }

    // ============================= Utilities ==============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select(".anime-grid a.anime-card")
        val animeList = cards.map { card ->
            SAnime.create().apply {
                val animeUrl = card.attr("href")
                setUrlWithoutDomain(animeUrl)
                title = card.selectFirst("p.text-xs, .card-overlay p")?.text() ?: card.attr("title") ?: ""
                thumbnail_url = card.selectFirst("img")?.attr("abs:src")
            }
        }

        val hasNextPage = document.select("a").any { it.text().contains("Next") }
        return AnimesPage(animeList, hasNextPage)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "jpn"
        private val PREF_LANG_ENTRIES = listOf("Japanese", "English")
        private val PREF_LANG_VALUES = listOf("jpn", "eng")

        private const val PREF_SUB_KEY = "pref_sub_type"
        private const val PREF_SUB_DEFAULT = "sub"
        private val PREF_SUB_ENTRIES = listOf("Sub", "Dub", "Soft Sub")
        private val PREF_SUB_VALUES = listOf("sub", "dub", "ssub")

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_TITLE = "Title Display Style"
        private val PREF_TITLE_STYLE_ENTRIES = listOf("User Preferred", "Romaji", "English", "Native")
        private val PREF_TITLE_STYLE_VALUES = listOf("userPreferred", "romaji", "english", "native")
        private const val PREF_TITLE_STYLE_DEFAULT = "userPreferred"

        private const val PREF_MARK_FILLERS_KEY = "mark_filler_episodes"
        private const val PREF_MARK_FILLERS_DEFAULT = true

        private const val PREF_HIDE_FILLERS_KEY = "hide_filler_episodes"
        private const val PREF_HIDE_FILLERS_DEFAULT = false

        private const val PREF_EP_META_KEY = "pref_episode_meta"
        private const val PREF_EP_META_DEFAULT = true

        private const val PREF_ANILIST_EP_TITLES_KEY = "anilist_episode_titles"
        private const val PREF_ANILIST_EP_TITLES_DEFAULT = true

        private const val PREF_STRIP_HTML_KEY = "pref_strip_html"
        private const val PREF_STRIP_HTML_DEFAULT = true

        private const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
        private const val PREF_CF_UA_KEY = "cf_bypass_ua"
        private const val PREF_CF_UA_TITLE = "Custom User-Agent"
        private const val PREF_CF_UA_DEFAULT = DEFAULT_UA
        private val PREF_CF_UA_SUMMARY = "Custom User-Agent string for the Cloudflare WebView bypass. Leave blank to use the default."

        private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
        private const val JIKAN_API_URL = "https://api.jikan.moe/v4"
        private const val ANIZIP_API_URL = "https://api.ani.zip/mappings"

        private val EPISODE_TITLE_REGEX = Regex("""^Episode\s+(\d+(?:\.\d+)?)\s*[-–—:]\s*(.+)$""", RegexOption.IGNORE_CASE)
    }
}

class AniDBCloudflareInterceptor(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
) : Interceptor {
    private val cfInterceptor = CloudflareInterceptor(client)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isBaseUrl = request.url.host == baseUrlProvider().toHttpUrlOrNull()?.host
        return if (isBaseUrl) {
            cfInterceptor.intercept(chain)
        } else {
            chain.proceed(request)
        }
    }
}
