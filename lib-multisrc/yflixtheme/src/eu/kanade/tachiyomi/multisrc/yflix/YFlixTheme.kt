package eu.kanade.tachiyomi.multisrc.yflix

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import aniyomi.lib.rapidshareextractor.RapidShareExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.LazyMutable
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

open class YFlixTheme(
    override val name: String,
    protected val domainList: List<String>,
    protected val defaultDomain: String = "https://${domainList.first()}",
    override val lang: String = "en",
) : AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest: Boolean = true

    protected open val context: Application by injectLazy()

    protected open val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    protected open val preferences by getPreferencesLazy {
        clearOldPrefs()
    }

    override val baseUrl by preferences.delegate(PREF_DOMAIN_KEY, defaultDomain)

    protected open val encdecHeaders by lazy {
        Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
        }.build()
    }

    protected open fun headersReferrerBuilder(url: String = baseUrl): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
        .add("Referer", "$url/")

    protected open var docHeaders by LazyMutable {
        headersReferrerBuilder().build()
    }

    protected open var rapidShareExtractor by LazyMutable {
        RapidShareExtractor(client, docHeaders, context)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tv-shows")

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/watch-movies-online")

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", page.toString())
            .also { builder ->
                YFlixThemeFilters.getFilters(filters).forEach {
                    it.addQueryParameters(builder)
                }
            }.build()
        return GET(url.toString(), docHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    protected open val moviesSelector = "article.content-card"

    protected open fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(moviesSelector).mapNotNull { item ->
            val link = item.selectFirst("a") ?: return@mapNotNull null
            val title = item.selectFirst("h3.card-title")?.text() ?: return@mapNotNull null

            SAnime.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                this.title = title
                thumbnail_url = item.selectFirst("img")?.absUrl("src")
            }
        }

        val hasNextPage = document.selectFirst("li.page-item a[rel=next]") != null

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    protected open fun Document.isMovie(): Boolean = location().contains("/movie/")

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val document = response.asJsoup()

        title = document.selectFirst("h1.detail-title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("div.detail-poster img")?.absUrl("src")
        val isMovie = document.isMovie()
        status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING

        genre = document.select("div.detail-meta-row:has(span.detail-meta-label:contains(Genres)) a")
            .eachText().joinToString()

        author = document.select("div.detail-meta-row:has(span.detail-meta-label:contains(Director)) a")
            .eachText().joinToString()

        val scorePosition = preferences.scorePosition
        val fancyScore = when (scorePosition) {
            SCORE_POS_TOP, SCORE_POS_BOTTOM -> document.getFancyScore()
            else -> ""
        }

        description = buildString {
            if (scorePosition == SCORE_POS_TOP && fancyScore.isNotEmpty()) {
                append(fancyScore)
                append("\n\n")
            }

            document.selectFirst("p.detail-desc")?.text()?.also { append("$it\n\n") }

            val type = if (isMovie) "Movie" else "TV Show"
            append("**Type:** $type\n")

            fun getInfo(label: String): String? = document.selectFirst("div.detail-meta-row:has(span.detail-meta-label:contains($label))")
                ?.selectFirst("span.detail-meta-value")?.text()?.trim()

            getInfo("Country")?.let { append("**Country:** $it\n") }
            getInfo("Released")?.let { append("**Released:** $it\n") }
            getInfo("Casts")?.let { append("**Casts:** $it\n") }

            document.selectFirst("span.badge-imdb")?.text()?.let {
                val rating = it.removePrefix("IMDb").removePrefix("IMDB").trim()
                if (rating.isNotEmpty()) append("**IMDb:** $rating")
            }

            document.getBackdropUrl()?.let { coverUrl ->
                if (coverUrl.isNotBlank()) {
                    append("\n\n![Cover]($coverUrl)")
                }
            }

            if (scorePosition == SCORE_POS_BOTTOM && fancyScore.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(fancyScore)
            }
        }
    }

    protected open fun Document.getBackdropUrl(): String? = selectFirst("div.detail-backdrop-img img")?.absUrl("src")

    protected open fun Document.getScore(): String? = selectFirst("span.badge-imdb")
        ?.text()
        ?.removePrefix("IMDb")
        ?.removePrefix("IMDB")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    protected open fun Document.getFancyScore(): String {
        val score = getScore()
        if (score.isNullOrBlank()) return ""

        return try {
            val scoreBig = BigDecimal(score.trim())
            if (scoreBig.compareTo(BigDecimal.ZERO) == 0) return ""

            val stars = scoreBig.divide(BigDecimal(2))
                .setScale(0, RoundingMode.HALF_UP)
                .toInt()
                .coerceIn(0, 5)

            val scoreString = scoreBig.stripTrailingZeros().toPlainString()

            buildString {
                append("★".repeat(stars))
                if (stars < 5) append("☆".repeat(5 - stars))
                append(" $scoreString")
            }
        } catch (_: NumberFormatException) {
            ""
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = YFlixThemeFilters.FILTER_LIST

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = baseUrl + anime.url
        val document = client.newCall(GET(animeUrl, docHeaders)).awaitSuccess().use { it.asJsoup() }

        val isMovie = document.isMovie()

        return if (isMovie) {
            listOf(
                SEpisode.create().apply {
                    url = anime.url
                    episode_number = 1F
                    name = "Movie"
                },
            )
        } else {
            document.select("div.episode-item[data-season][data-episode]")
                .map { element ->
                    val season = element.attr("data-season")
                    val episode = element.attr("data-episode")
                    SEpisode.create().apply {
                        url = "${anime.url}#s$season-e$episode"
                        episode_number = episode.toFloatOrNull() ?: 0F
                        name = "S$season E$episode"
                    }
                }
                .reversed()
                .ifEmpty { throw Exception("No episodes found.") }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used.")

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlParts = episode.url.split('#', limit = 2)
        val animeUrl = urlParts[0]
        val fragment = urlParts.getOrNull(1)
        val referer = baseUrl + animeUrl

        val document = client.newCall(GET(referer, docHeaders)).awaitSuccess().use { it.asJsoup() }
        val playerWrap = document.selectFirst("div.player-wrap")
            ?: throw Exception("Player not found")

        val sourceUrls: List<Pair<String, String>> = if (fragment != null) {
            // Serie: pedir sources via AJAX
            val seriesSlug = playerWrap.attr("data-series-slug").takeIf { it.isNotBlank() }
                ?: return emptyList()
            val seasonEp = fragment.removePrefix("s").split("-e", limit = 2)
            val season = seasonEp.getOrNull(0) ?: return emptyList()
            val ep = seasonEp.getOrNull(1) ?: return emptyList()

            val ajaxUrl = "$baseUrl/ajax/episode/sources?series=$seriesSlug&season=$season&episode=$ep"
            Log.d("YFlix", "AJAX series: $ajaxUrl")

            runCatching {
                client.newCall(GET(ajaxUrl, ajaxHeaders(referer)))
                    .awaitSuccess().use {
                        it.parseAs<EpisodeSourcesResponse>(json = json).sources
                            .map { src -> src.name to src.url }
                    }
            }.getOrElse {
                Log.e("YFlix", "Error AJAX series: ${it.message}")
                emptyList()
            }
        } else {
            // Película: leer JSON de data-sources
            val rawSources = playerWrap.attr("data-sources")
            Log.d("YFlix", "data-sources crudo: $rawSources")
            parseSourcesJson(rawSources)
        }

        Log.d("YFlix", "Servidores: ${sourceUrls.joinToString { it.first }}")

        // Intentar Alpha primero, luego el resto
        val prioritized = sourceUrls.sortedByDescending { (name, _) ->
            when (name) {
                "Alpha" -> 2
                "Beta" -> 1
                else -> 0
            }
        }

        return prioritized.parallelCatchingFlatMap { (serverName, iframeUrl) ->
            if (serverName !in preferences.hosterPref) return@parallelCatchingFlatMap emptyList()

            val absoluteUrl = when {
                iframeUrl.startsWith("//") -> "https:$iframeUrl"
                iframeUrl.startsWith("/") -> "$baseUrl$iframeUrl"
                else -> iframeUrl
            }

            Log.d("YFlix", "Extrayendo $serverName: $absoluteUrl")
            rapidShareExtractor.videosFromUrl(absoluteUrl, serverName, preferences.subLangPref)
        }
    }

    private fun parseSourcesJson(jsonString: String): List<Pair<String, String>> {
        if (jsonString.isBlank() || jsonString == "[]") return emptyList()
        return runCatching {
            json.decodeFromString<List<MovieSource>>(jsonString)
                .map { it.name to it.url }
        }.getOrElse {
            // Intentar desescapando entidades HTML (&quot; → ")
            runCatching {
                val unescaped = org.jsoup.parser.Parser.unescapeEntities(jsonString, false)
                json.decodeFromString<List<MovieSource>>(unescaped)
                    .map { it.name to it.url }
            }.getOrElse {
                Log.e("YFlix", "Error parseando sources JSON: ${it.message}")
                emptyList()
            }
        }
    }

    // ============================= Utilities ==============================

    protected open fun ajaxHeaders(referer: String) = docHeaders.newBuilder()
        .set("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    protected open fun parseDate(dateStr: String): Long = runCatching { DATE_FORMATTER.parse(dateStr)?.time }.getOrNull() ?: 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.qualityPref
        val server = preferences.serverPref
        val qualities = QUALITIES.reversed()

        return sortedWith(
            compareByDescending<Video> {
                it.quality.contains(quality, true) && it.quality.startsWith(server, true)
            }
                .thenByDescending { it.quality.contains(quality, true) }
                .thenByDescending { it.quality.startsWith(server, true) }
                .thenByDescending { video -> qualities.indexOfFirst { video.quality.contains(it) } },
        )
    }

    // ============================== Preferences ==============================

    protected open val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    protected open val SharedPreferences.subLangPref by preferences.delegate(PREF_SUB_LANG_KEY, PREF_SUB_LANG_DEFAULT)
    protected open val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    protected open val SharedPreferences.hosterPref by preferences.delegate(PREF_HOSTER_KEY, SERVERS.toSet())
    protected open val SharedPreferences.scorePosition by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)

    protected open fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val domain = getString(PREF_DOMAIN_KEY, defaultDomain) ?: return this
        val domainHost = domain.toHttpUrlOrNull()?.host ?: domain
        if (domainHost !in domainList) {
            edit().putString(PREF_DOMAIN_KEY, defaultDomain).apply()
        }
        val hostToggle = getStringSet(PREF_HOSTER_KEY, SERVERS.toSet()) ?: return this
        if (hostToggle.any { it !in SERVERS } || !hostToggle.contains("Alpha")) {
            edit()
                .putStringSet(PREF_HOSTER_KEY, SERVERS.toSet())
                .putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
                .apply()
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = domainList,
            entryValues = domainList.map { "https://$it" },
            default = defaultDomain,
            summary = "%s",
        ) {
            docHeaders = headersReferrerBuilder(it).build()
            rapidShareExtractor = RapidShareExtractor(client, docHeaders, context)
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITIES,
            entryValues = QUALITIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SUB_LANG_KEY,
            title = "Preferred sub language",
            entries = SUB_LANGS,
            entryValues = SUB_LANGS,
            default = PREF_SUB_LANG_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVERS,
            entryValues = SERVERS,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            title = "Score display position",
            entries = PREF_SCORE_POSITION_ENTRIES,
            entryValues = PREF_SCORE_POSITION_VALUES,
            default = PREF_SCORE_POSITION_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/disable servers",
            entries = SERVERS,
            entryValues = SERVERS,
            default = SERVERS.toSet(),
            summary = "Select which video server to show in the episode list",
        )
    }

    companion object {
        protected const val PREF_DOMAIN_KEY = "pref_domain_key"

        const val PREF_QUALITY_KEY = "pref_quality_key"
        protected val QUALITIES = listOf("1080p", "720p", "480p", "360p")
        protected val PREF_QUALITY_DEFAULT = QUALITIES.first()

        const val PREF_SUB_LANG_KEY = "pref_sub_lang_key"
        protected val SUB_LANGS = listOf(
            "English", "Arabic", "Chinese", "French", "German",
            "Indonesian", "Italian", "Japanese", "Korean", "Persian",
            "Portuguese", "Russian", "Spanish", "Turkish", "Urdu", "Vietnamese",
        )
        internal val PREF_SUB_LANG_DEFAULT = SUB_LANGS.first()

        const val PREF_SERVER_KEY = "pref_server_key"
        protected val SERVERS = listOf(
            "Alpha", "Beta", "Gamma", "Delta", "Sigma",
            "Omega", "Zeta", "Theta", "Iota", "Kappa",
            "Server 1", "Server 2", "Server 3", "Server 4", "Server 5",
            "Server 6", "Server 7", "Server 8", "Server 9", "Server 10",
        )
        protected val PREF_SERVER_DEFAULT = SERVERS.first()

        const val PREF_HOSTER_KEY = "pref_hoster_key"

        protected const val PREF_SCORE_POSITION_KEY = "score_position"
        protected const val SCORE_POS_TOP = "top"
        protected const val SCORE_POS_BOTTOM = "bottom"
        protected const val SCORE_POS_NONE = "none"
        protected const val PREF_SCORE_POSITION_DEFAULT = SCORE_POS_TOP
        protected val PREF_SCORE_POSITION_ENTRIES = listOf(
            "Top of description",
            "Bottom of description",
            "Don't show",
        )
        protected val PREF_SCORE_POSITION_VALUES = listOf(
            SCORE_POS_TOP,
            SCORE_POS_BOTTOM,
            SCORE_POS_NONE,
        )

        protected val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }
    }
}
