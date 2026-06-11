package eu.kanade.tachiyomi.animeextension.en.miruro

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.decodeHex
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class Miruro :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Miruro.tv"
    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override var baseUrl by LazyMutable { preferences.preferredMirror }

    private val SharedPreferences.preferredMirror by preferences.delegate(PREF_MIRROR_KEY, PREF_MIRROR_DEFAULT)
    private val SharedPreferences.markFillers by preferences.delegate(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT)
    private val SharedPreferences.hideFillers by preferences.delegate(PREF_HIDE_FILLERS_KEY, PREF_HIDE_FILLERS_DEFAULT)
    private val SharedPreferences.includeAllSubTypes by preferences.delegate(PREF_INCLUDE_ALL_SUB_TYPES_KEY, PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT)
    private val SharedPreferences.stripHtml by preferences.delegate(PREF_STRIP_HTML_KEY, PREF_STRIP_HTML_DEFAULT)
    private val SharedPreferences.mergeAcrossProviders by preferences.delegate(PREF_MERGE_PROVIDERS_KEY, PREF_MERGE_PROVIDERS_DEFAULT)
    private val SharedPreferences.useAnilistEpisodeTitles by preferences.delegate(PREF_ANILIST_EP_TITLES_KEY, PREF_ANILIST_EP_TITLES_DEFAULT)
    private val SharedPreferences.preferredCountries by preferences.delegate(PREF_COUNTRY_KEY, PREF_COUNTRY_DEFAULT)
    private val SharedPreferences.preferredTitleStyle by preferences.delegate(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT)
    private val SharedPreferences.preferredProvider by preferences.delegate(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT)
    private val SharedPreferences.preferredSubType by preferences.delegate(PREF_SUB_TYPE_KEY, PREF_SUB_TYPE_DEFAULT)
    private val SharedPreferences.preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    companion object {
        const val PREFIX_SEARCH = "miruro:"

        private val PIPE_KEY = "71951034f8fbcf53d89db52ceb3dc22c".decodeHex()

        private const val PREF_PROVIDER_KEY = "preferred_provider"
        private const val PREF_PROVIDER_TITLE = "Preferred Provider"
        private val PREF_PROVIDER_ENTRIES = listOf("Kiwi", "Bee")
        private val PREF_PROVIDER_VALUES = listOf("kiwi", "bee")
        private const val PREF_PROVIDER_DEFAULT = "kiwi"

        private const val PREF_SUB_TYPE_KEY = "preferred_sub_type"
        private const val PREF_SUB_TYPE_TITLE = "Preferred Sub/Dub"
        private val PREF_SUB_TYPE_ENTRIES = listOf("Sub", "Dub", "Soft Sub")
        private val PREF_SUB_TYPE_VALUES = listOf("sub", "dub", "ssub")
        private const val PREF_SUB_TYPE_DEFAULT = "sub"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = listOf("1080", "720", "480", "360")
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_TITLE_STYLE_KEY = "preferred_title_style"
        private const val PREF_TITLE_STYLE_TITLE = "Title Display Style"
        private val PREF_TITLE_STYLE_ENTRIES = listOf("User Preferred", "Romaji", "English", "Native")
        private val PREF_TITLE_STYLE_VALUES = listOf("userPreferred", "romaji", "english", "native")
        private const val PREF_TITLE_STYLE_DEFAULT = "userPreferred"

        private const val PREF_MARK_FILLERS_KEY = "mark_filler_episodes"
        private const val PREF_MARK_FILLERS_TITLE = "Mark filler episodes"
        private const val PREF_MARK_FILLERS_DEFAULT = true

        private const val PREF_HIDE_FILLERS_KEY = "hide_filler_episodes"
        private const val PREF_HIDE_FILLERS_TITLE = "Hide filler episodes"
        private const val PREF_HIDE_FILLERS_DEFAULT = false

        private const val PREF_INCLUDE_ALL_SUB_TYPES_KEY = "include_all_sub_types"
        private const val PREF_INCLUDE_ALL_SUB_TYPES_TITLE = "Include all sub/dub streams"
        private const val PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT = true

        private const val PREF_STRIP_HTML_KEY = "strip_html_descriptions"
        private const val PREF_STRIP_HTML_TITLE = "Strip HTML from descriptions"
        private const val PREF_STRIP_HTML_DEFAULT = true

        private const val PREF_MERGE_PROVIDERS_KEY = "merge_across_providers"
        private const val PREF_MERGE_PROVIDERS_TITLE = "Merge episodes across providers"
        private const val PREF_MERGE_PROVIDERS_DEFAULT = true

        private const val PREF_ANILIST_EP_TITLES_KEY = "anilist_episode_titles"
        private const val PREF_ANILIST_EP_TITLES_TITLE = "AniList episode titles"
        private const val PREF_ANILIST_EP_TITLES_DEFAULT = true

        private const val PREF_COUNTRY_KEY = "preferred_countries"
        private const val PREF_COUNTRY_TITLE = "Country of origin"
        private val PREF_COUNTRY_ENTRIES = listOf("Japan (Anime)", "China (Donghua)", "South Korea (Aeni)", "Taiwan")
        private val PREF_COUNTRY_VALUES = listOf("JP", "CN", "KR", "TW")
        private val PREF_COUNTRY_DEFAULT = emptySet<String>()

        private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"
        private const val JIKAN_API_URL = "https://api.jikan.moe/v4"
        private const val ANIZIP_API_URL = "https://api.ani.zip/mappings"

        private val EPISODE_TITLE_REGEX = Regex("""^Episode\s+(\d+(?:\.\d+)?)\s*[-–—:]\s*(.+)$""", RegexOption.IGNORE_CASE)

        private const val PREF_MIRROR_KEY = "preferred_mirror"
        private const val PREF_MIRROR_TITLE = "Preferred mirror"
        private val MIRROR_ENTRIES = listOf("miruro.tv", "miruro.to", "miruro.bz", "miruro.ru")
        private val MIRROR_VALUES = MIRROR_ENTRIES.map { "https://www.$it" }
        private val PREF_MIRROR_DEFAULT = MIRROR_VALUES.first()
    }

    private val jikanClient: OkHttpClient = network.client.newBuilder()
        .rateLimitHost("$JIKAN_API_URL/".toHttpUrl(), permits = 1, period = 1, unit = TimeUnit.SECONDS)
        .build()

    private val metaClient: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private fun fetchJsonWithRetry(request: Request, tag: String, attempts: Int = 2): JSONObject? {
        repeat(attempts) { attempt ->
            try {
                metaClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return JSONObject(resp.body.string())
                    }
                    Log.e("Miruro", "$tag request failed: HTTP ${resp.code}")
                    if (resp.code in 400..499) return null
                }
            } catch (e: Exception) {
                Log.e("Miruro", "$tag request error: ${e.message}")
            }
            if (attempt < attempts - 1) Thread.sleep(1000)
        }
        return null
    }

    private val settingsCountries: List<String>
        get() = PREF_COUNTRY_VALUES.filter { it in preferences.preferredCountries }

    private fun resolveCountries(filterCountry: String? = null): List<String> =
        if (filterCountry != null && filterCountry != "all") listOf(filterCountry) else settingsCountries

    private suspend fun fetchMergedPages(requests: List<Request>, parser: (Response) -> AnimesPage): AnimesPage {
        if (requests.size == 1) {
            return client.newCall(requests[0]).awaitSuccess().use(parser)
        }

        val pages = requests.parallelMap { request ->
            runCatching {
                client.newCall(request).awaitSuccess().use(parser)
            }.getOrElse {
                Log.e("Miruro", "Country fetch failed: ${it.message}")
                AnimesPage(emptyList(), false)
            }
        }

        val seen = HashSet<String>()
        val merged = mutableListOf<SAnime>()
        val iterators = pages.map { it.animes.iterator() }
        var added = true
        while (added) {
            added = false
            for (iterator in iterators) {
                if (iterator.hasNext()) {
                    val anime = iterator.next()
                    if (seen.add(anime.url)) merged.add(anime)
                    added = true
                }
            }
        }
        return AnimesPage(merged, pages.any { it.hasNextPage })
    }

    private fun browseRequest(page: Int, sort: String, country: String? = null): Request {
        val query = buildPipeQuery(
            "type" to "ANIME",
            "status" to "RELEASING",
            "page" to page,
            "perPage" to 20,
            "sort" to sort,
            "countryOfOrigin" to country,
        )
        return buildPipeRequest("search/browse", "GET", query = query)
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val countries = settingsCountries
        return if (countries.size <= 1) {
            client.newCall(browseRequest(page, "TRENDING_DESC", countries.firstOrNull())).awaitSuccess().use(::popularAnimeParse)
        } else {
            fetchMergedPages(countries.map { browseRequest(page, "TRENDING_DESC", it) }, ::popularAnimeParse)
        }
    }

    override fun popularAnimeRequest(page: Int): Request = browseRequest(page, "TRENDING_DESC", settingsCountries.firstOrNull())
    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeListResponse(response)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val countries = settingsCountries
        return if (countries.size <= 1) {
            client.newCall(browseRequest(page, "UPDATED_AT_DESC", countries.firstOrNull())).awaitSuccess().use(::latestUpdatesParse)
        } else {
            fetchMergedPages(countries.map { browseRequest(page, "UPDATED_AT_DESC", it) }, ::latestUpdatesParse)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page, "UPDATED_AT_DESC", settingsCountries.firstOrNull())
    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeListResponse(response)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.getOrNull(0) != "watch") {
                throw Exception("Unsupported url")
            }
            val anilistId = url.pathSegments.getOrNull(1) ?: throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_SEARCH}$anilistId", filters)
        }

        if (query.startsWith(PREFIX_SEARCH)) {
            val anilistId = query.removePrefix(PREFIX_SEARCH)
            val request = buildPipeRequest("info/$anilistId", "GET")
            val jsonObj = client.newCall(request).awaitSuccess().use { response ->
                JSONObject(response.use(::decryptResponse))
            }
            val media = jsonObj.optJSONObject("media") ?: jsonObj
            val id = media.optInt("id", 0)
            val malId = media.optInt("idMal", 0).takeIf { it > 0 }
            if (id > 0) cachedAnimeMeta = AnimeMeta(id, malId)
            return AnimesPage(listOf(parseAnimeFromMedia(media)), false)
        }

        val params = MiruroFilters.getSearchParameters(filters)
        val countries = resolveCountries(params.country)

        return if (countries.size > 1) {
            fetchMergedPages(countries.map { searchAnimeRequestWithCountry(page, query, filters, it) }, ::searchAnimeParse)
        } else {
            client.newCall(searchAnimeRequestWithCountry(page, query, filters, countries.firstOrNull())).awaitSuccess().use(::searchAnimeParse)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        searchAnimeRequestWithCountry(page, query, filters, resolveCountries(MiruroFilters.getSearchParameters(filters).country).firstOrNull())

    private fun searchAnimeRequestWithCountry(page: Int, query: String, filters: AnimeFilterList, country: String?): Request {
        if (query.isNotEmpty()) {
            return buildPipeRequest("search", "GET", query = buildPipeQuery(
                "q" to query, "type" to "ANIME", "limit" to 20, "offset" to (page - 1) * 20, "countryOfOrigin" to country
            ))
        }

        val params = MiruroFilters.getSearchParameters(filters)
        val queryParams = buildPipeQuery("type" to "ANIME", "page" to page, "perPage" to 20, "countryOfOrigin" to country)

        if (params.sort != "all") queryParams.put("sort", params.sort)
        if (params.season != "all") queryParams.put("season", params.season)
        if (params.year != "all") queryParams.put("year", params.year.toInt())
        if (params.status != "all") queryParams.put("status", params.status)
        if (params.genres.isNotEmpty()) queryParams.put("genre", JSONArray().apply { params.genres.forEach { put(it) } })
        if (params.formats.isNotEmpty()) queryParams.put("format", JSONArray().apply { params.formats.forEach { put(it) } })
        if (params.tags.isNotEmpty()) queryParams.put("tag", JSONArray().apply { params.tags.forEach { put(it) } })

        return buildPipeRequest("search/browse", "GET", query = queryParams)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeListResponse(response, listOf("results", "data"))

    override fun animeDetailsRequest(anime: SAnime): Request = buildPipeRequest("info/${anime.url}", "GET")

    override fun animeDetailsParse(response: Response): SAnime {
        val jsonObj = JSONObject(response.use(::decryptResponse))
        val media = jsonObj.optJSONObject("media") ?: jsonObj
        val titleObj = media.optJSONObject("title") ?: JSONObject()

        val anilistId = media.optInt("id", 0)
        val malId = media.optInt("idMal", 0).takeIf { it > 0 }
        if (anilistId > 0) {
            cachedAnimeMeta?.takeIf { it.anilistId == anilistId }?.let { if (malId != null) it.malId = malId }
                ?: run { cachedAnimeMeta = AnimeMeta(anilistId, malId) }
        }

        val title = resolveTitle(titleObj, preferences.preferredTitleStyle)
        val thumbnail = extractCoverImage(media.opt("coverImage"))
        val bannerImage = extractBannerImage(media.opt("bannerImage"))
        val coverUrl = thumbnail.ifEmpty { bannerImage }
            .ifEmpty { anilistId.takeIf { it > 0 }?.let { resolveAnilistMeta(it).coverImage }.orEmpty() }

        val description = if (preferences.stripHtml) {
            media.optString("description", "")
                .replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
                .replace("</p>".toRegex(RegexOption.IGNORE_CASE), "\n")
                .replace("<[^>]+>".toRegex(), "").trim()
        } else {
            media.optString("description", "")
        }

        val genres = media.optJSONArray("genres")?.let { (0 until it.length()).mapNotNull { i -> it.optString(i) }.joinToString() }

        val status = when (media.optString("status", "").uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "NOT_YET_RELEASED" -> SAnime.UNKNOWN
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }

        val studio = extractMainStudio(media.opt("studios"))

        val finalTitle = title.ifBlank {
            listOf(
                titleObj.optString("userPreferred", "").trim(),
                titleObj.optString("romaji", "").trim(),
                titleObj.optString("english", "").trim(),
                titleObj.optString("native", "").trim(),
            ).firstOrNull { it.isNotBlank() } ?: "Unknown Title"
        }

        return SAnime.create().apply {
            this.title = finalTitle
            thumbnail_url = coverUrl
            this.description = description
            genre = genres
            this.status = status
            author = studio
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        currentAnilistId = anime.url.toInt()
        return buildPipeRequest("episodes", "GET", query = buildPipeQuery("anilistId" to currentAnilistId))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonObj = JSONObject(response.use(::decryptResponse))
        val providers = jsonObj.optJSONObject("providers") ?: return emptyList()
        val preferredProvider = preferences.preferredProvider
        val preferredSubType = preferences.preferredSubType
        val mergeAcrossProviders = preferences.mergeAcrossProviders
        val anilistId = currentAnilistId ?: extractAnilistIdFromPipeRequest(response.request.url.toString())

        val fillerEpisodes = if (preferences.markFillers || preferences.hideFillers) {
            resolveFillerEpisodes(anilistId, providers, preferredProvider)
        } else emptySet()

        val episodes = mutableListOf<SEpisode>()
        providers.optJSONObject(preferredProvider)?.let {
            episodes.addAll(parseEpisodesFromProvider(it, preferredProvider, preferredSubType, fillerEpisodes))
        }

        if (mergeAcrossProviders && episodes.isNotEmpty()) {
            val preferredNumbers = episodes.map { it.episode_number }.toSet()
            for (key in providers.keys()) {
                if (key == preferredProvider || key == "hop") continue
                val other = parseEpisodesFromProvider(providers.getJSONObject(key), key, preferredSubType, fillerEpisodes)
                episodes.addAll(other.filter { it.episode_number !in preferredNumbers })
            }
        } else if (episodes.isEmpty()) {
            for (key in providers.keys()) {
                if (key == preferredProvider || key == "hop") continue
                val other = parseEpisodesFromProvider(providers.getJSONObject(key), key, preferredSubType, fillerEpisodes)
                if (other.isNotEmpty()) {
                    episodes.addAll(other)
                    break
                }
            }
        }

        val result = episodes.reversed().let {
            if (preferences.useAnilistEpisodeTitles) applyAnilistEpisodeTitles(it, anilistId) else it
        }
        return if (preferences.hideFillers) result.filter { !it.scanlator.orEmpty().contains("Filler") } else result
    }

    private fun parseEpisodesFromProvider(
        providerData: JSONObject, provider: String, preferredSubType: String, fillerEpisodes: Set<Float>
    ): List<SEpisode> {
        val episodesObj = providerData.optJSONObject("episodes") ?: return emptyList()
        val subTypes = when (provider) {
            "kiwi" -> listOf("sub", "dub")
            "bee" -> listOf("ssub", "sub", "dub")
            else -> listOf("sub", "dub")
        }

        val episodeMap = mutableMapOf<Float, MutableMap<String, String>>()
        val episodeMeta = mutableMapOf<Float, Pair<Double, String>>()

        for (subType in subTypes) {
            episodesObj.optJSONArray(subType)?.let { arr ->
                for (i in 0 until arr.length()) {
                    val ep = arr.getJSONObject(i)
                    val number = ep.optDouble("number", 0.0).toFloat()
                    val id = ep.optString("id", "")
                    val title = ep.optString("title", "")
                    episodeMap.getOrPut(number) { mutableMapOf() }[subType] = id
                    if (number !in episodeMeta) episodeMeta[number] = ep.optDouble("number", 0.0) to title
                }
            }
        }

        return episodeMap.keys.mapNotNull { number ->
            val subTypeIds = episodeMap[number] ?: return@mapNotNull null
            val (rawNumber, title) = episodeMeta[number] ?: return@mapNotNull null
            buildMergedEpisode(rawNumber, title, provider, preferredSubType, subTypeIds, subTypes, fillerEpisodes)
        }
    }

    private fun buildMergedEpisode(
        number: Double, title: String, provider: String, preferredSubType: String,
        subTypeIds: Map<String, String>, allSubTypes: List<String>, fillerEpisodes: Set<Float>
    ): SEpisode {
        val defaultSubType = subTypeIds.keys.firstOrNull { it == preferredSubType }
            ?: allSubTypes.firstOrNull { it in subTypeIds } ?: subTypeIds.keys.first()
        val episodeId = subTypeIds[defaultSubType] ?: ""

        val episodeIdObj = JSONObject().apply {
            put("episodeId", episodeId)
            put("provider", provider)
            put("defaultSubType", defaultSubType)
            put("subTypes", JSONObject(subTypeIds))
        }

        val audioLabels = subTypeIds.keys.map {
            when (it) {
                "sub" -> "Sub"
                "dub" -> "Dub"
                "ssub" -> "Soft Sub"
                else -> it.replaceFirstChar { c -> c.uppercase() }
            }
        }.sortedWith(compareBy { mapOf("Sub" to 0, "Dub" to 1)[it] ?: 2 })

        val isFiller = fillerEpisodes.contains(number.toFloat())

        return SEpisode.create().apply {
            episode_number = number.toFloat()
            name = if (title.isNotEmpty()) "Episode ${number.toInt()}: $title" else "Episode ${number.toInt()}"
            setUrlWithoutDomain(episodeIdObj.toString())
            scanlator = audioLabels.joinToString(" & ") + if (isFiller) " • Filler" else ""
        }
    }

    @Volatile private var currentEpisodeData: JSONObject? = null
    @Volatile private var currentAnilistId: Int? = null

    private data class EpisodeMeta(var title: String? = null, var thumbnail: String? = null, var overview: String? = null)
    private data class AnimeMeta(
        val anilistId: Int, var malId: Int? = null, var fillerEpisodes: Set<Float>? = null,
        var episodeMeta: Map<Float, EpisodeMeta>? = null, var coverImage: String? = null, var anilistFetched: Boolean = false
    )
    @Volatile private var cachedAnimeMeta: AnimeMeta? = null

    override fun videoListRequest(episode: SEpisode): Request {
        currentEpisodeData = JSONObject(episode.url)
        return buildPipeRequest("sources", "GET", query = buildPipeQuery(
            "episodeId" to currentEpisodeData!!.getString("episodeId"),
            "provider" to currentEpisodeData!!.getString("provider"),
            "category" to currentEpisodeData!!.getString("defaultSubType")
        ))
    }

    override fun videoListParse(response: Response): List<Video> {
        val episodeData = currentEpisodeData ?: return emptyList()
        val provider = episodeData.optString("provider", "")
        val subTypesObj = episodeData.optJSONObject("subTypes")
        val defaultSubType = episodeData.optString("defaultSubType", "sub")

        val videos = parseStreamsFromResponse(response, defaultSubType).toMutableList()

        if (preferences.includeAllSubTypes && subTypesObj != null && subTypesObj.length() > 1) {
            val requests = mutableListOf<Pair<String, Request>>()
            for (key in subTypesObj.keys()) {
                if (key == defaultSubType) continue
                val id = subTypesObj.optString(key, "")
                if (id.isNotEmpty()) {
                    requests.add(key to buildPipeRequest("sources", "GET", query = buildPipeQuery(
                        "episodeId" to id, "provider" to provider, "category" to key
                    )))
                }
            }
            videos.addAll(requests.parallelCatchingFlatMapBlocking { (subType, req) ->
                client.newCall(req).awaitSuccess().use { parseStreamsFromResponse(it, subType) }
            })
        }
        return videos
    }

    private fun parseStreamsFromResponse(response: Response, subType: String?): List<Video> {
        val json = JSONObject(response.use(::decryptResponse))
        val streams = json.optJSONArray("streams") ?: return emptyList()
        val label = when (subType) {
            "sub" -> "Sub"
            "dub" -> "Dub"
            "ssub" -> "Soft Sub"
            else -> subType?.replaceFirstChar { it.uppercase() }
        }

        return (0 until streams.length()).mapNotNull { i ->
            val s = streams.getJSONObject(i)
            if (s.optString("type") != "hls") return@mapNotNull null
            val url = s.optString("url", "")
            if (url.isEmpty()) return@mapNotNull null
            Video(url, if (label != null) "${s.optString("quality")} • $label" else s.optString("quality"), url)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(PREF_MIRROR_KEY, PREF_MIRROR_DEFAULT, PREF_MIRROR_TITLE, "", MIRROR_ENTRIES, MIRROR_VALUES) { baseUrl = it }
        screen.addListPreference(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT, PREF_PROVIDER_TITLE, "", PREF_PROVIDER_ENTRIES, PREF_PROVIDER_VALUES)
        screen.addListPreference(PREF_SUB_TYPE_KEY, PREF_SUB_TYPE_DEFAULT, PREF_SUB_TYPE_TITLE, "", PREF_SUB_TYPE_ENTRIES, PREF_SUB_TYPE_VALUES)
        screen.addListPreference(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT, PREF_QUALITY_TITLE, "", PREF_QUALITY_ENTRIES, PREF_QUALITY_VALUES)
        screen.addListPreference(PREF_TITLE_STYLE_KEY, PREF_TITLE_STYLE_DEFAULT, PREF_TITLE_STYLE_TITLE, "", PREF_TITLE_STYLE_ENTRIES, PREF_TITLE_STYLE_VALUES)
        screen.addSwitchPreference(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT, PREF_MARK_FILLERS_TITLE, "")
        screen.addSwitchPreference(PREF_HIDE_FILLERS_KEY, PREF_HIDE_FILLERS_DEFAULT, PREF_HIDE_FILLERS_TITLE, "")
        screen.addSwitchPreference(PREF_INCLUDE_ALL_SUB_TYPES_KEY, PREF_INCLUDE_ALL_SUB_TYPES_DEFAULT, PREF_INCLUDE_ALL_SUB_TYPES_TITLE, "")
        screen.addSwitchPreference(PREF_STRIP_HTML_KEY, PREF_STRIP_HTML_DEFAULT, PREF_STRIP_HTML_TITLE, "")
        screen.addSwitchPreference(PREF_MERGE_PROVIDERS_KEY, PREF_MERGE_PROVIDERS_DEFAULT, PREF_MERGE_PROVIDERS_TITLE, "")
        screen.addSwitchPreference(PREF_ANILIST_EP_TITLES_KEY, PREF_ANILIST_EP_TITLES_DEFAULT, PREF_ANILIST_EP_TITLES_TITLE, "Use official episode titles from AniList when available.")
        screen.addSetPreference(PREF_COUNTRY_KEY, PREF_COUNTRY_DEFAULT, PREF_COUNTRY_TITLE, "", PREF_COUNTRY_ENTRIES, PREF_COUNTRY_VALUES)
    }

    private fun resolveFillerEpisodes(anilistId: Int?, providers: JSONObject, preferredProvider: String): Set<Float> {
        if (anilistId == null) return emptySet()
        val data = providers.optJSONObject(preferredProvider) ?: return emptySet()
        val maxEp = findMaxEpisodeNumber(data)
        if (maxEp <= 0) return emptySet()
        val malId = cachedAnimeMeta?.malId ?: fetchMalId(anilistId) ?: return emptySet()
        return fetchFillerEpisodes(malId, maxEp)
    }

    private fun findMaxEpisodeNumber(providerData: JSONObject): Float {
        val episodes = providerData.optJSONObject("episodes") ?: return 0f
        var max = 0f
        for (key in episodes.keys()) {
            episodes.optJSONArray(key)?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.optDouble("number", 0.0)?.toFloat()?.let { if (it > max) max = it }
                }
            }
        }
        return max
    }

    private fun anilistMalIdRequest(anilistId: Int): Request {
        val query = $$"query media($id: Int, $type: MediaType) { Media(id: $id, type: $type) { idMal } }"
        val variables = buildJsonObject { put("id", anilistId); put("type", "ANIME") }
        val body = FormBody.Builder().add("query", query).add("variables", kotlinx.serialization.json.Json.encodeToString(variables)).build()
        return POST(ANILIST_GRAPHQL_URL, body = body)
    }

    private fun fetchMalId(anilistId: Int): Int? = try {
        metaClient.newCall(anilistMalIdRequest(anilistId)).execute().parseAs<AnilistMalIdResponse>().data.media.idMal
    } catch (e: Exception) { Log.e("Miruro", "Failed MAL ID: ${e.message}"); null }

    private fun anilistMetaRequest(anilistId: Int): Request {
        val query = $$"query media($id: Int, $type: MediaType) { Media(id: $id, type: $type) { idMal coverImage { extraLarge large medium } streamingEpisodes { title thumbnail } } }"
        val variables = buildJsonObject { put("id", anilistId); put("type", "ANIME") }
        val body = FormBody.Builder().add("query", query).add("variables", kotlinx.serialization.json.Json.encodeToString(variables)).build()
        return POST(ANILIST_GRAPHQL_URL, body = body)
    }

    private fun resolveAnilistMeta(anilistId: Int): AnimeMeta {
        cachedAnimeMeta?.takeIf { it.anilistId == anilistId && it.anilistFetched }?.let { return it }
        val meta = cachedAnimeMeta?.takeIf { it.anilistId == anilistId } ?: AnimeMeta(anilistId).also { cachedAnimeMeta = it }
        val episodeMeta = mutableMapOf<Float, EpisodeMeta>()

        try {
            fetchJsonWithRetry(GET("$ANIZIP_API_URL?anilist_id=$anilistId"), "ani.zip")?.optJSONObject("episodes")?.let { episodes ->
                for (key in episodes.keys()) {
                    val number = key.toFloatOrNull() ?: continue
                    val ep = episodes.optJSONObject(key) ?: continue
                    val titleObj = ep.optJSONObject("title")
                    val title = listOf("en", "x-jat", "ja").mapNotNull { titleObj?.optString(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("Episode ", true) }
                    val thumbnail = ep.optString("image").takeIf { it.isNotBlank() }
                    val overview = ep.optString("overview").takeIf { it.isNotBlank() }
                    if (title != null || thumbnail != null || overview != null) {
                        episodeMeta[number] = EpisodeMeta(title, thumbnail, overview)
                    }
                }
            }
        } catch (e: Exception) { Log.e("Miruro", "ani.zip error: ${e.message}") }

        try {
            fetchJsonWithRetry(anilistMetaRequest(anilistId), "AniList")?.optJSONObject("data")?.optJSONObject("Media")?.let { media ->
                media.optInt("idMal", 0).takeIf { it > 0 }?.let { meta.malId = it }
                extractCoverImage(media.opt("coverImage")).takeIf { it.isNotEmpty() }?.let { meta.coverImage = it }
                media.optJSONArray("streamingEpisodes")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val match = EPISODE_TITLE_REGEX.find(ep.optString("title", "")) ?: continue
                        val number = match.groupValues[1].toFloatOrNull() ?: continue
                        val title = match.groupValues[2].trim()
                        val thumbnail = ep.optString("thumbnail").takeIf { it.isNotBlank() }
                        val existing = episodeMeta.getOrPut(number) { EpisodeMeta() }
                        if (existing.title.isNullOrEmpty()) existing.title = title
                        if (existing.thumbnail.isNullOrEmpty()) existing.thumbnail = thumbnail
                    }
                }
            }
        } catch (e: Exception) { Log.e("Miruro", "AniList error: ${e.message}") }

        meta.episodeMeta = episodeMeta
        meta.anilistFetched = true
        return meta
    }

    private fun formatEpisodeNumber(n: Float) = if (n % 1f == 0f) n.toInt().toString() else n.toString()

    private val episodeExtraSetters by lazy {
        val clazz = SEpisode.create().javaClass
        clazz.getMethod("setSummary", String::class.java) to clazz.getMethod("setPreview_url", String::class.java)
    }

    private fun trySetEpisodeExtras(episode: SEpisode, summary: String?, previewUrl: String?) {
        try {
            episodeExtraSetters.first.invoke(episode, summary)
            episodeExtraSetters.second.invoke(episode, previewUrl)
        } catch (e: Exception) { Log.e("Miruro", "Extras error: ${e.message}") }
    }

    private fun applyAnilistEpisodeTitles(episodes: List<SEpisode>, anilistId: Int?): List<SEpisode> {
        if (anilistId == null || episodes.isEmpty()) return episodes
        val metaMap = resolveAnilistMeta(anilistId).episodeMeta.orEmpty()
        if (metaMap.isEmpty()) return episodes

        episodes.forEach { ep ->
            metaMap[ep.episode_number]?.let { m ->
                val label = "Episode ${formatEpisodeNumber(ep.episode_number)}"
                m.title?.let { title ->
                    val current = ep.name.removePrefix(label).removePrefix(":").trim()
                    if (current.isEmpty() || !current.equals(title, true)) ep.name = "$label: $title"
                }
                trySetEpisodeExtras(ep, m.overview, m.thumbnail)
            }
        }
        return episodes
    }

    private fun fetchFillerEpisodes(malId: Int, maxEpisode: Float = Float.MAX_VALUE): Set<Float> {
        val result = mutableSetOf<Float>()
        var page = 1
        var hasNext = true
        while (hasNext && page <= 10) {
            try {
                val dto = jikanClient.newCall(GET("$JIKAN_API_URL/anime/$malId/episodes?page=$page")).execute().parseAs<JikanEpisodesDto>()
                dto.data.forEach { if (it.number.toFloat() <= maxEpisode && it.filler) result.add(it.number.toFloat()) }
                hasNext = dto.pagination.hasNextPage
                page++
            } catch (e: Exception) { Log.e("Miruro", "Jikan error: ${e.message}"); break }
        }
        return result
    }

    private fun extractAnilistIdFromPipeRequest(url: String): Int? = try {
        val encoded = url.substringAfter("e=", "")
        if (encoded.isEmpty()) null else {
            val decoded = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            JSONObject(String(decoded)).optJSONObject("query")?.optInt("anilistId", -1)?.takeIf { it > 0 }
        }
    } catch (_: Exception) { null }

    private fun buildPipeRequest(path: String, method: String = "GET", query: JSONObject = JSONObject(), body: JSONObject = JSONObject()): Request {
        val payload = JSONObject().apply {
            put("path", path)
            put("method", method)
            put("query", query)
            put("body", if (body.length() == 0) JSONObject.NULL else body)
            put("version", "0.2.0")
        }
        val encoded = Base64.encodeToString(payload.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return GET("$baseUrl/api/secure/pipe?e=$encoded", Headers.headersOf("Accept", "*/*", "Referer", "$baseUrl/"))
    }

    private fun buildPipeQuery(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> if (v != null) put(k, when (v) { is Number, is String, is Boolean -> v; else -> v.toString() }) }
    }

    private fun decryptResponse(response: Response): String {
        val obfuscated = response.header("x-obfuscated") ?: "1"
        val bodyBytes = response.body.bytes()
        val bodyStr = String(bodyBytes, Charsets.UTF_8).trim()
        if (obfuscated != "2") return bodyStr

        val decoded = Base64.decode(bodyStr, Base64.URL_SAFE)
        val data = decoded.mapIndexed { i, b -> (b.toInt() xor PIPE_KEY[i % PIPE_KEY.size].toInt()).toByte() }.toByteArray()
        return GZIPInputStream(java.io.ByteArrayInputStream(data)).use { it.bufferedReader(Charsets.UTF_8).readText() }
    }

    private fun extractCoverImage(cover: Any?): String = when (cover) {
        is JSONObject -> cover.optString("extraLarge", "").ifEmpty { cover.optString("large", "") }.ifEmpty { cover.optString("medium", "") }
        is String -> cover
        else -> ""
    }

    private fun extractBannerImage(banner: Any?): String = if (banner is String) banner else ""

    private fun extractMainStudio(studios: Any?): String {
        val edges = when (studios) {
            is JSONObject -> studios.optJSONArray("edges")
            is JSONArray -> studios
            else -> return ""
        } ?: return ""
        for (i in 0 until edges.length()) {
            edges.optJSONObject(i)?.takeIf { it.optBoolean("isMain") }?.optJSONObject("node")?.optString("name")?.let { return it }
        }
        return edges.optJSONObject(0)?.optJSONObject("node")?.optString("name") ?: ""
    }

    private fun parseAnimeListResponse(response: Response, fallbackKeys: List<String> = emptyList()): AnimesPage {
        val json = response.use(::decryptResponse)
        val arr = try { JSONArray(json) } catch (_: Exception) {
            JSONObject(json).let { obj ->
                obj.optJSONArray("media") ?: fallbackKeys.firstNotNullOfOrNull { obj.optJSONArray(it) } ?: return AnimesPage(emptyList(), false)
            }
        }
        return AnimesPage((0 until arr.length()).map { parseAnimeFromMedia(arr.getJSONObject(it)) }, arr.length() >= 20)
    }

    private fun resolveTitle(titleObj: JSONObject, style: String): String {
        val r = titleObj.optString("romaji", "").trim()
        val e = titleObj.optString("english", "").trim()
        val n = titleObj.optString("native", "").trim()
        val u = titleObj.optString("userPreferred", "").trim()
        return when (style) {
            "romaji" -> r.ifEmpty { e.ifEmpty { n.ifEmpty { u } } }
            "english" -> e.ifEmpty { r.ifEmpty { n.ifEmpty { u } } }
            "native" -> n.ifEmpty { r.ifEmpty { e.ifEmpty { u } } }
            else -> u.ifEmpty { r.ifEmpty { e.ifEmpty { n } } }
        }
    }

    private fun parseAnimeFromMedia(media: JSONObject): SAnime {
        val titleObj = media.optJSONObject("title") ?: JSONObject()
        val title = resolveTitle(titleObj, preferences.preferredTitleStyle)
        val r = titleObj.optString("romaji", "").trim()
        val e = titleObj.optString("english", "").trim()
        val n = titleObj.optString("native", "").trim()
        val u = titleObj.optString("userPreferred", "").trim()
        val finalTitle = title.ifBlank { listOf(u, r, e, n).firstOrNull { it.isNotBlank() } ?: "Unknown Title" }
        val id = media.optInt("id", 0).toString()
        val thumb = extractCoverImage(media.opt("coverImage"))
        val banner = extractBannerImage(media.opt("bannerImage"))
        return SAnime.create().apply {
            this.title = finalTitle
            thumbnail_url = thumb.ifEmpty { banner.ifEmpty { null } }
            setUrlWithoutDomain(id)
        }
    }
}

@Serializable
class AnilistMalIdResponse(val data: DataObject) {
    @Serializable class DataObject(@SerialName("Media") val media: MediaObject) {
        @Serializable class MediaObject(@SerialName("idMal") val idMal: Int? = null)
    }
}

@Serializable
class JikanEpisodesDto(val data: List<JikanEpisodeDataDto>, val pagination: JikanPaginationDto) {
    @Serializable class JikanEpisodeDataDto(@SerialName("mal_id") val number: Int, val filler: Boolean)
    @Serializable class JikanPaginationDto(@SerialName("has_next_page") val hasNextPage: Boolean)
}
