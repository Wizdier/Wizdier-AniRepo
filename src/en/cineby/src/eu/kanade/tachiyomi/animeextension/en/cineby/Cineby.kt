package eu.kanade.tachiyomi.animeextension.en.cineby

import android.content.SharedPreferences
import android.os.Build
import android.text.InputType
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parallelCatchingMapNotNull
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Cineby :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Cineby"

    private val preferences: SharedPreferences by getPreferencesLazy {
        clearOldPrefs()
    }

    override val baseUrl
        get() = preferences.domainPref

    /**
     * TMDB metadata routing:
     * All metadata requests target the official TMDB API host. The interceptor
     * below authenticates them with the user's personal API key (Settings),
     * or transparently rewrites them to the keyless Cineby/Videasy proxy when
     * no key is set or the key is rejected.
     */
    private val apiUrl = "https://$TMDB_HOST/3"

    override val client = network.client.newBuilder()
        .addInterceptor(::tmdbInterceptor)
        .build()

    private fun tmdbInterceptor(chain: okhttp3.Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.host != TMDB_HOST) return chain.proceed(request)

        fun proxied(): Request {
            val proxyUrl = url.newBuilder()
                .host(PROXY_HOST)
                .removeAllQueryParameters("api_key")
                .build()
            return request.newBuilder().url(proxyUrl).build()
        }

        val apiKey = preferences.tmdbApiKey.trim()
        if (apiKey.isEmpty()) return chain.proceed(proxied())

        val authedUrl = url.newBuilder()
            .setQueryParameter("api_key", apiKey)
            .setQueryParameter("language", preferences.tmdbLanguage)
            .build()
        val response = chain.proceed(request.newBuilder().url(authedUrl).build())

        // Invalid/revoked key or TMDB hiccup: fall back to the proxy so the
        // source keeps working instead of erroring out.
        if (response.code in setOf(401, 403, 429) || response.code >= 500) {
            response.close()
            return chain.proceed(proxied())
        }
        return response
    }

    private fun apiOrigin(url: String): String = url.replace(Regex("""https?://www\."""), "https://")

    override val lang = "en"
    override val supportsLatest = true

    private val extractor by lazy { CinebyExtractor(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("trending")
            addPathSegment("all")
            addPathSegment("week")
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val types = if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

        return types.parallelCatchingMapNotNull { mediaType ->
            client.newCall(latestUpdatesRequest(page, mediaType))
                .awaitSuccess()
                .use { latestUpdatesParse(it) }
        }.let { animePages ->
            val animes = animePages.flatMap { it.animes }
            val hasNextPage = animePages.any { it.hasNextPage }
            AnimesPage(animes, hasNextPage)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    private fun latestUpdatesRequest(page: Int, mediaType: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", "primary_release_date.desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("vote_count.gte", "50")
            addQueryParameter("primary_release_date.lte", today())
        }.build()
        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseMediaPage(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        if (query.startsWith("https://")) {
            val url = query.toHttpUrl()
            if (url.host != baseUrl.toHttpUrl().host) {
                throw Exception("Unsupported url")
            }
            val type = url.pathSegments.getOrNull(0)
                ?: throw Exception("Unsupported url")
            val rawId = url.pathSegments.getOrNull(1)
                ?: throw Exception("Unsupported url")
            if (type !in listOf("movie", "tv")) throw Exception("Unsupported url")
            return getSearchAnime(page, "${PREFIX_ID}$type/$rawId", filters)
        }

        // Deep link from CinebyUrlActivity: "id:<type>/<id>"
        if (query.startsWith(PREFIX_ID)) {
            val rawPath = query.substringAfter(PREFIX_ID)
            if (!DEEP_LINK_PATH_REGEX.matches(rawPath)) {
                return AnimesPage(emptyList(), false)
            }
            val url = "/$rawPath"
            val tempAnime = SAnime.create().apply { this.url = url }
            return runCatching {
                getAnimeDetails(tempAnime).apply { this.url = url }
                    .let(::listOf)
            }.getOrElse { emptyList() }
                .let { AnimesPage(it, false) }
        }

        val typeIndex = filters.filterIsInstance<CinebyFilters.TypeFilter>()
            .firstOrNull()?.state ?: CinebyFilters.TYPE_ALL
        val isAnimes = typeIndex == CinebyFilters.TYPE_ANIMES
        val isAll = typeIndex == CinebyFilters.TYPE_ALL

        val rawPages: List<PageDto<MediaItemDto>> = if (query.isNotBlank() && isAll) {
            listOfNotNull(fetchMediaPage(textSearchRequest(page, query, "multi")))
        } else {
            val mediaTypes: List<String> = when (typeIndex) {
                CinebyFilters.TYPE_MOVIES -> listOf("movie")
                CinebyFilters.TYPE_TV, CinebyFilters.TYPE_ANIMES -> listOf("tv")
                else -> if (preferences.latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")
            }
            mediaTypes.parallelMapNotNull { mediaType ->
                val request = if (query.isNotBlank()) {
                    textSearchRequest(page, query, mediaType)
                } else {
                    discoverRequest(page, mediaType, filters, animesOnly = isAnimes)
                }
                fetchMediaPage(request)
            }
        }

        var items = rawPages.flatMap { it.results }
        if (query.isNotBlank() && isAll) {
            items = items.filter { it.mediaType == "movie" || it.mediaType == "tv" }
        }
        if (isAnimes) items = items.filter(::isLikelyAnime)
        val hasNextPage = rawPages.any { it.page < it.totalPages }

        return AnimesPage(items.map(::mediaItemToSAnime), hasNextPage)
    }

    private suspend fun fetchMediaPage(request: Request): PageDto<MediaItemDto>? = runCatching {
        client.newCall(request).awaitSuccess()
            .parseAs<PageDto<MediaItemDto>>()
    }.getOrNull()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used; getSearchAnime is overridden")

    override fun searchAnimeParse(response: Response): AnimesPage = parseMediaPage(response)

    private fun textSearchRequest(page: Int, query: String, mediaType: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(mediaType)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
        }.build()
        return GET(url)
    }

    private fun discoverRequest(
        page: Int,
        mediaType: String,
        filters: AnimeFilterList,
        animesOnly: Boolean,
    ): Request {
        val sortIndex = filters.filterIsInstance<CinebyFilters.SortFilter>()
            .firstOrNull()?.state ?: CinebyFilters.SORT_POPULAR
        val sortBy = when (sortIndex) {
            CinebyFilters.SORT_RATING -> "vote_average.desc"
            CinebyFilters.SORT_RECENT -> if (mediaType == "movie") {
                "primary_release_date.desc"
            } else {
                "first_air_date.desc"
            }
            else -> "popularity.desc"
        }

        val genreMap = if (mediaType == "movie") {
            CinebyFilters.MOVIE_GENRE_MAP
        } else {
            CinebyFilters.TV_GENRE_MAP
        }
        val userGenres = filters.filterIsInstance<CinebyFilters.GenreFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.mapNotNull { genreMap[it.name] }
            .orEmpty()
        val genreIds = if (animesOnly) (listOf("16") + userGenres).distinct() else userGenres
        val genreParam = genreIds.joinToString(",")

        val providers = filters.filterIsInstance<CinebyFilters.WatchProviderFilter>()
            .firstOrNull()?.state
            ?.filter { it.state }
            ?.joinToString("|") { it.id }
            .orEmpty()

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (genreParam.isNotBlank()) addQueryParameter("with_genres", genreParam)
            if (animesOnly) addQueryParameter("with_original_language", "ja")
            if (providers.isNotBlank()) {
                addQueryParameter("with_watch_providers", providers)
                addQueryParameter("watch_region", "US")
            }
            when (sortIndex) {
                CinebyFilters.SORT_RATING -> {
                    addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RATING_SORT)
                }
                CinebyFilters.SORT_RECENT -> {
                    addQueryParameter("vote_count.gte", MIN_VOTES_FOR_RECENT_SORT)
                    val dateField = if (mediaType == "movie") {
                        "primary_release_date.lte"
                    } else {
                        "first_air_date.lte"
                    }
                    addQueryParameter(dateField, today())
                }
            }
        }.build()
        return GET(url)
    }

    private fun today(): String = synchronized(DATE_FORMATTER) {
        DATE_FORMATTER.format(Date())
    }

    private fun isLikelyAnime(item: MediaItemDto): Boolean {
        val isJapanese = item.originalLanguage == "ja" || "JP" in item.originCountries
        return isJapanese && ANIMATION_GENRE_ID in item.genreIds
    }

    // ============================== Filters ==============================
    override fun getFilterList(): AnimeFilterList = CinebyFilters.getFilterList()

    // ============================== Details ==============================
    // anime.url uses native cineby.sc paths: "/movie/<id>" or "/tv/<id>".
    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    private fun animeUrlToId(anime: SAnime): Pair<String, String> = animeUrlRegex.find(anime.url)?.let { matchResult ->
        val type = matchResult.groupValues[1]
        val rawId = matchResult.groupValues[2]
        type to rawId
    } ?: throw IllegalArgumentException("Invalid anime URL: ${anime.url}")

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addQueryParameter("append_to_response", "external_ids")
        }.build()
        return GET(url)
    }

    override fun animeDetailsParse(response: Response): SAnime = try {
        if ("/movie/" in response.request.url.toString()) {
            movieDetailsParse(response)
        } else {
            tvDetailsParse(response)
        }
    } catch (e: Exception) {
        throw Exception("Failed to parse details.", e)
    }

    private fun movieDetailsParse(response: Response): SAnime {
        val movie = response.parseAs<MovieDetailDto>()
        return SAnime.create().apply {
            title = movie.title
            url = "/movie/${movie.id}"
            thumbnail_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = movie.productionCompanies.joinToString { it.name }
            genre = movie.genres.joinToString { it.name }
            status = parseStatus(movie.status)
            description = buildString {
                movie.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** Movie",
                    movie.voteAverage.takeIf { it > 0f }?.let {
                        "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                    },
                    movie.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    movie.releaseDate?.takeIf(String::isNotBlank)?.let { "**Release Date:** $it" },
                    movie.countries?.takeIf { it.isNotEmpty() }
                        ?.let { "**Country:** ${it.joinToString()}" },
                    movie.originalTitle?.takeIf {
                        it.isNotBlank() && it.trim() != movie.title.trim()
                    }?.let { "**Original Title:** $it" },
                    movie.runtime?.takeIf { it > 0 }?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        "**Runtime:** ${if (hours > 0) "${hours}h " else ""}${minutes}m"
                    },
                    movie.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    movie.externalIds?.imdbId?.let {
                        "**[IMDB](https://www.imdb.com/title/$it)**"
                    },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                movie.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    private fun tvDetailsParse(response: Response): SAnime {
        val tv = response.parseAs<TvDetailDto>()
        return SAnime.create().apply {
            title = tv.name
            url = "/tv/${tv.id}"
            thumbnail_url = tv.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = tv.productionCompanies.joinToString { it.name }
            artist = tv.networks.joinToString { it.name }
            genre = tv.genres.joinToString { it.name }
            status = parseStatus(tv.status)
            description = buildString {
                tv.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** TV Show",
                    tv.voteAverage.takeIf { it > 0f }?.let {
                        "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}"
                    },
                    tv.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    tv.firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
                    tv.lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
                    tv.countries?.takeIf { it.isNotEmpty() }
                        ?.let { "**Country:** ${it.joinToString()}" },
                    tv.originalName?.takeIf {
                        it.isNotBlank() && it.trim() != tv.name.trim()
                    }?.let { "**Original Name:** $it" },
                    tv.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    tv.externalIds?.imdbId?.let {
                        "**[IMDB](https://www.imdb.com/title/$it)**"
                    },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                tv.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    // ========================== Related Titles ============================
    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val (type, id) = animeUrlToId(anime)
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("page", "1")
        }.build()
        return GET(url, headers)
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (type, _) = animeUrlToId(anime)
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        return if (type == "tv") {
            val tv = response.parseAs<TvDetailDto>()
            val extraData = Triple(
                tv.name,
                tv.firstAirDate?.take(4) ?: "",
                tv.externalIds?.imdbId ?: "",
            )
            val extraDataEncoded = extraData.toJsonString()
            tv.seasons
                .filter { it.seasonNumber > 0 }
                .parallelCatchingFlatMap { season ->
                    val seasonDetail = client.newCall(
                        GET("$apiUrl/tv/${tv.id}/season/${season.seasonNumber}"),
                    ).awaitSuccess().parseAs<TvSeasonDetailDto>()
                    seasonDetail.episodes.map { episode ->
                        SEpisode.create().apply {
                            name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                            episode_number = episode.episodeNumber.toFloat()
                            scanlator = "Season ${season.seasonNumber}"
                            date_upload = parseDate(episode.airDate)
                            url = "tv/${tv.id}/${season.seasonNumber}/" +
                                "${episode.episodeNumber}#$extraDataEncoded"
                            if (preferences.episodeMetaPref) {
                                applyEpisodeMeta(
                                    episode = this,
                                    overview = episode.overview,
                                    stillPath = episode.stillPath,
                                    runtime = episode.runtime,
                                    rating = episode.voteAverage,
                                )
                            }
                        }
                    }
                }
                .sortedWith(
                    compareByDescending<SEpisode> {
                        it.scanlator?.substringAfter(" ")?.toIntOrNull()
                    }.thenByDescending { it.episode_number },
                )
        } else {
            val movie = response.parseAs<MovieDetailDto>()
            val extraData = Triple(
                movie.title,
                movie.releaseDate?.take(4) ?: "",
                movie.externalIds?.imdbId ?: "",
            )
            val extraDataEncoded = extraData.toJsonString()
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = parseDate(movie.releaseDate)
                    url = "movie/${movie.id}#$extraDataEncoded"
                    if (preferences.episodeMetaPref) {
                        applyEpisodeMeta(
                            episode = this,
                            overview = movie.overview,
                            stillPath = movie.backdropPath,
                            runtime = movie.runtime,
                            rating = movie.voteAverage,
                        )
                    }
                },
            )
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    // ========================= Episode Metadata =========================

    /**
     * Reflection accessors for optional SEpisode fields that only exist on
     * newer app forks (AniZen / Animetail / current Aniyomi): summary and
     * preview_url. Resolved once against the app-provided runtime class;
     * null on apps without support, so this silently no-ops there.
     */
    private val episodeExtraSetters by lazy {
        val clazz = SEpisode.create().javaClass
        val summary = runCatching { clazz.getMethod("setSummary", String::class.java) }.getOrNull()
        val preview = runCatching { clazz.getMethod("setPreview_url", String::class.java) }.getOrNull()
        summary to preview
    }

    private fun applyEpisodeMeta(
        episode: SEpisode,
        overview: String?,
        stillPath: String?,
        runtime: Int?,
        rating: Float,
    ) {
        val (summarySetter, previewSetter) = episodeExtraSetters
        runCatching {
            val summaryText = buildString {
                val info = listOfNotNull(
                    rating.takeIf { it > 0f }?.let { "★ ${String.format(Locale.US, "%.1f", it)}" },
                    runtime?.takeIf { it > 0 }?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                    },
                )
                if (info.isNotEmpty()) append(info.joinToString(" • "))
                overview?.takeIf(String::isNotBlank)?.let {
                    if (isNotEmpty()) append("\n")
                    append(it)
                }
            }
            if (summaryText.isNotBlank()) summarySetter?.invoke(episode, summaryText)
            stillPath?.takeIf(String::isNotBlank)?.let {
                previewSetter?.invoke(episode, "$TMDB_IMAGE_URL/w500$it")
            }
        }
    }

    // ============================ Video Links ============================
    @RequiresApi(Build.VERSION_CODES.N)
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (path, extraDataEncoded) = episode.url.split("#", limit = 2)
        val (title, year, imdbId) =
            extraDataEncoded.parseAs<Triple<String, String, String>>()

        return extractor.videosFromUrl(
            path = path,
            title = title,
            year = year,
            imdbId = imdbId,
            baseUrl = apiOrigin(baseUrl),
            enabledServers = preferences.enabledServerNames,
            subLimit = preferences.subLimitPref.toIntOrNull()
                ?: PREF_SUB_LIMIT_DEFAULT.toInt(),
            qualityPref = preferences.qualityPref,
        )
    }

    // ============================== Settings ==============================
    private val SharedPreferences.domainPref by preferences.delegate(
        PREF_DOMAIN_KEY,
        PREF_DOMAIN_DEFAULT,
    )
    private val SharedPreferences.qualityPref by preferences.delegate(
        PREF_QUALITY_KEY,
        PREF_QUALITY_DEFAULT,
    )
    private val SharedPreferences.latestPref by preferences.delegate(
        PREF_LATEST_KEY,
        PREF_LATEST_DEFAULT,
    )
    private val SharedPreferences.subLimitPref by preferences.delegate(
        PREF_SUB_LIMIT_KEY,
        PREF_SUB_LIMIT_DEFAULT,
    )
    private val SharedPreferences.enabledServerNames: Set<String> by preferences.delegate(
        PREF_SERVERS_KEY,
        PREF_SERVERS_DEFAULT,
    )
    private val SharedPreferences.tmdbApiKey by preferences.delegate(
        PREF_TMDB_KEY_KEY,
        PREF_TMDB_KEY_DEFAULT,
    )
    private val SharedPreferences.tmdbLanguage by preferences.delegate(
        PREF_TMDB_LANG_KEY,
        PREF_TMDB_LANG_DEFAULT,
    )
    private val SharedPreferences.episodeMetaPref by preferences.delegate(
        PREF_EPISODE_META_KEY,
        PREF_EPISODE_META_DEFAULT,
    )

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            .removePrefix("https://")
        val invalidDomain = domain !in DOMAIN_ENTRIES

        if (invalidDomain) {
            edit().putString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT).apply()
        }

        // Drop server names removed from the catalog; restore defaults
        // if nothing valid remains (catalog pruning happens occasionally).
        val storedServers = getStringSet(PREF_SERVERS_KEY, null)
        if (storedServers != null) {
            val knownServers = CinebyExtractor.SERVER_DISPLAY_NAMES.toSet()
            val validServers = storedServers.intersect(knownServers)
            if (validServers != storedServers) {
                val healed = validServers.ifEmpty { PREF_SERVERS_DEFAULT }
                edit().putStringSet(PREF_SERVERS_KEY, healed).apply()
            }
        }

        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            entries = DOMAIN_ENTRIES.toList(),
            entryValues = DOMAIN_VALUES.toList(),
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("2160p", "1080p", "720p", "480p", "360p"),
            entryValues = listOf("2160", "1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_LATEST_KEY,
            title = "Preferred 'Latest' Page",
            entries = listOf("Movies", "TV Shows"),
            entryValues = listOf("movie", "tv"),
            default = PREF_LATEST_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_SUB_LIMIT_KEY,
            title = "Subtitle Search Limit",
            summary = "Limit subtitle count. Current: ${preferences.subLimitPref}",
            getSummary = { "Limit subtitle count. Current: $it" },
            default = PREF_SUB_LIMIT_DEFAULT,
            inputType = InputType.TYPE_CLASS_NUMBER,
            onChange = { _, newValue ->
                val n = newValue.toIntOrNull()
                (n != null && n >= 0)
            },
        )

        // Display "Name (Language)" but persist bare display names so
        // the catalog code can match them as keys.
        screen.addSetPreference(
            key = PREF_SERVERS_KEY,
            title = "Enabled Servers",
            entries = CinebyExtractor.VIDEASY_SERVERS.map { server ->
                val lang = server.audioLabel ?: "Unknown"
                "${server.displayName} ($lang)"
            },
            entryValues = CinebyExtractor.SERVER_DISPLAY_NAMES,
            default = PREF_SERVERS_DEFAULT,
            summary = "Select servers to enable. Languages shown per server.",
        )

        // ========================= TMDB Metadata =========================
        screen.addEditTextPreference(
            key = PREF_TMDB_KEY_KEY,
            title = "TMDB API Key",
            summary = if (preferences.tmdbApiKey.isBlank()) {
                "Not set - using Cineby's built-in metadata proxy. " +
                    "Paste your personal TMDB API key (v3) for direct, " +
                    "rate-limit-free metadata. Same key works in AniZen's TMDB tracker."
            } else {
                "Key set (${preferences.tmdbApiKey.take(4)}…) - metadata is fetched " +
                    "directly from TMDB with your key."
            },
            getSummary = { value ->
                if (value.isBlank()) {
                    "Not set - using Cineby's built-in metadata proxy. " +
                        "Paste your personal TMDB API key (v3) for direct, " +
                        "rate-limit-free metadata. Same key works in AniZen's TMDB tracker."
                } else {
                    "Key set (${value.take(4)}…) - metadata is fetched directly from TMDB with your key."
                }
            },
            default = PREF_TMDB_KEY_DEFAULT,
            dialogMessage = "Get a free API key at themoviedb.org → Settings → API. " +
                "Use the 'API Key (v3 auth)' value. Leave empty to use the built-in proxy.",
            validate = { TMDB_KEY_REGEX.matches(it.trim()) },
            validationMessage = { "Invalid TMDB v3 key - it should be a 32-character hex string" },
            onChange = { _, newValue ->
                val v = newValue.trim()
                v.isEmpty() || TMDB_KEY_REGEX.matches(v)
            },
        )

        screen.addListPreference(
            key = PREF_TMDB_LANG_KEY,
            title = "TMDB Metadata Language",
            entries = TMDB_LANG_ENTRIES,
            entryValues = TMDB_LANG_VALUES,
            default = PREF_TMDB_LANG_DEFAULT,
            summary = "Language for titles, overviews and episode data " +
                "(only applies when a TMDB API key is set). Current: %s",
        )

        screen.addSwitchPreference(
            key = PREF_EPISODE_META_KEY,
            title = "TMDB Episode Metadata",
            default = PREF_EPISODE_META_DEFAULT,
            summary = "Show TMDB episode thumbnails, descriptions, ratings and " +
                "runtimes in the episode list (on apps that support it, like AniZen).",
        )
    }

    // ============================= Utilities ==============================
    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages
        val animeList = pageDto.results.map(::mediaItemToSAnime)
        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime = SAnime.create().apply {
        title = media.realTitle
        val type = media.mediaType
            ?: if (media.title != null) "movie" else "tv"
        url = "/$type/${media.id}"
        thumbnail_url = media.posterPath
            ?.let { "https://image.tmdb.org/t/p/w500$it" }
    }

    private fun parseStatus(status: String?): Int = when (status) {
        "Released", "Ended" -> SAnime.COMPLETED
        "Returning Series", "In Production" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    private fun parseDate(dateStr: String?): Long = runCatching {
        synchronized(DATE_FORMATTER) {
            DATE_FORMATTER.parse(dateStr ?: "")?.time ?: 0L
        }
    }.getOrDefault(0L)

    companion object {
        // Deep-link prefix shared with CinebyUrlActivity.
        const val PREFIX_ID = "id:"

        private val DEEP_LINK_PATH_REGEX = Regex("""(movie|tv)/\d+""")

        private val animeUrlRegex = Regex("""/(movie|tv)/(\d+)""")

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }

        private const val ANIMATION_GENRE_ID = 16

        // Rating sort uses a higher floor: TMDB has many obscure titles
        // with a handful of perfect-score votes that would dominate.
        private const val MIN_VOTES_FOR_RATING_SORT = "200"
        private const val MIN_VOTES_FOR_RECENT_SORT = "50"

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private val DOMAIN_ENTRIES = arrayOf("www.cineby.at", "www.cineplay.to", "www.fmovies.nz")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "movie"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "25"

        private const val PREF_SERVERS_KEY = "pref_servers_v2"
        private val PREF_SERVERS_DEFAULT =
            setOf("Neon", "Yoru", "Cypher", "Sage")

        // ============================ TMDB =============================
        private const val TMDB_HOST = "api.themoviedb.org"
        private const val PROXY_HOST = "db.videasy.to"
        private const val TMDB_IMAGE_URL = "https://image.tmdb.org/t/p"

        // TMDB v3 keys are 32-char hex strings.
        private val TMDB_KEY_REGEX = Regex("""^[a-f0-9]{32}$""", RegexOption.IGNORE_CASE)

        private const val PREF_TMDB_KEY_KEY = "pref_tmdb_api_key"
        private const val PREF_TMDB_KEY_DEFAULT = ""

        private const val PREF_TMDB_LANG_KEY = "pref_tmdb_language"
        private const val PREF_TMDB_LANG_DEFAULT = "en-US"
        private val TMDB_LANG_ENTRIES = listOf(
            "English (US)", "English (UK)", "Spanish (Spain)", "Spanish (Latin America)",
            "Portuguese (Brazil)", "French", "German", "Italian", "Russian",
            "Arabic", "Hindi", "Bengali", "Japanese", "Korean", "Chinese (Simplified)",
            "Chinese (Traditional)", "Indonesian", "Turkish", "Vietnamese", "Thai",
        )
        private val TMDB_LANG_VALUES = listOf(
            "en-US", "en-GB", "es-ES", "es-MX",
            "pt-BR", "fr-FR", "de-DE", "it-IT", "ru-RU",
            "ar-SA", "hi-IN", "bn-BD", "ja-JP", "ko-KR", "zh-CN",
            "zh-TW", "id-ID", "tr-TR", "vi-VN", "th-TH",
        )

        private const val PREF_EPISODE_META_KEY = "pref_tmdb_episode_meta"
        private const val PREF_EPISODE_META_DEFAULT = true
    }
}
