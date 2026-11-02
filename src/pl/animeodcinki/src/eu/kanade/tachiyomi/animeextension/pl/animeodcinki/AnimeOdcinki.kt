package eu.kanade.tachiyomi.animeextension.pl.animeodcinki

import android.util.Base64
import aniyomi.lib.cdaextractor.CdaExtractor
import aniyomi.lib.sibnetextractor.SibnetExtractor
import aniyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeOdcinki : ParsedAnimeHttpSource() {
    override val name = "Anime-Odcinki"
    override val baseUrl = "https://anime-odcinki.pl"
    override val lang = "pl"
    override val supportsLatest = true
    private var pendingSearchQuery = ""
    private var pendingPage = 1
    private val catalogJson = Json { ignoreUnknownKeys = true }
    private val cdaExtractor by lazy { CdaExtractor(client) }
    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }

    override fun popularAnimeRequest(page: Int): Request {
        pendingPage = page
        return GET("$baseUrl/anime-lista/", headers)
    }
    override fun popularAnimeParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = parseCatalog(response, "", pendingPage)
    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)
    override fun latestUpdatesSelector(): String = ".season-item[href*='/anime/'], #vod-recent-anime-list a[href*='/anime/']"
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = "#vod-recent-anime-btn"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        pendingSearchQuery = query
        pendingPage = page
        return GET("$baseUrl/anime-lista/", headers)
    }
    override fun searchAnimeParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = parseCatalog(response, pendingSearchQuery, pendingPage)
    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()
    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text().orEmpty()
        description = document.select("#vod-episodes-container ~ *").text().takeIf { it.isNotBlank() }
            ?: document.select(".vod-description, .anime-description").text()
        thumbnail_url = document.selectFirst("img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
        genre = document.select("a[href*='/gatunki/']").joinToString { it.text() }
        status = SAnime.UNKNOWN
    }

    override fun episodeListSelector(): String = "#vod-episodes-container .vod-ep-item[data-url]"
    override fun episodeFromElement(element: Element): SEpisode {
        val raw = element.attr("data-url")
        val url = runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault("")
        val title = element.selectFirst(".vod-ep-name")?.text().orEmpty().ifBlank { element.text() }
        val number = element.attr("data-num").toFloatOrNull()
            ?: Regex("\\d+(?:[.,]\\d+)?").find(title)?.value?.replace(',', '.')?.toFloatOrNull() ?: 0f
        return SEpisode.create().apply {
            name = title.ifBlank { "Odcinek $number" }
            episode_number = number
            this.url = url
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val playerHeaders = headers.newBuilder().add("Referer", referer).build()
        val servers = document.select("#vod-player-box .vod-tab[data-token]").mapNotNull { tab ->
            val playerUrl = runCatching {
                String(Base64.decode(tab.attr("data-token").reversed(), Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault("")
            val label = tab.text().trim()
            playerUrl.takeIf { it.startsWith("http") }?.let { Triple(it, label, playerHeaders) }
        }
        fun matching(host: String, label: String) = servers.filter { (url, name, _) ->
            host in url || name.contains(label, ignoreCase = true)
        }
        val cdaVideos = matching("cda.pl", "CDA").flatMap { (url, label, requestHeaders) ->
            runCatching { cdaExtractor.getVideosFromUrl(url, requestHeaders, "$label - ") }.getOrDefault(emptyList())
        }
        if (cdaVideos.isNotEmpty()) return sortedVideos(cdaVideos)

        val vkVideos = matching("vk.com", "VK").ifEmpty { matching("vkvideo.ru", "VK") }
            .parallelCatchingFlatMapBlocking { (url, label, _) -> vkExtractor.videosFromUrl(url, "$label - ") }
        if (vkVideos.isNotEmpty()) return sortedVideos(vkVideos)

        val sibnetVideos = matching("sibnet.ru", "Sibnet").flatMap { (url, label, _) ->
            runCatching { sibnetExtractor.videosFromUrl(url, "$label - ") }.getOrDefault(emptyList())
        }
        return sortedVideos(sibnetVideos)
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    private fun qualityRank(quality: String): Int {
        val height = Regex("(?i)(\\d{3,4})\\s*p?").find(quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (height <= 1080) height else 1080 - (height - 1080)
    }

    private fun sortedVideos(videos: List<Video>): List<Video> =
        videos.distinctBy { it.url }.sortedWith(compareByDescending<Video> { qualityRank(it.quality) }.thenBy { it.quality })
    override fun videoUrlParse(document: Document): String = document.selectFirst("video source[src], video[src]")?.absUrl("src").orEmpty()

    private fun animeFromElement(element: Element): SAnime {
        val link = if (element.tagName() == "a") element else element.selectFirst("a[href]")
        val image = element.selectFirst("img")
        return SAnime.create().apply {
            title = element.selectFirst(".vod-ep-name, h2, h3, .title")?.text()
                ?: link?.text()?.ifBlank { image?.attr("alt").orEmpty() }.orEmpty()
            setUrlWithoutDomain(link?.absUrl("href").orEmpty())
            thumbnail_url = image?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
        }
    }

    private fun parseCatalog(response: okhttp3.Response, query: String, page: Int): eu.kanade.tachiyomi.animesource.model.AnimesPage {
        val html = response.body.string()
        val encoded = Regex("const hexData = \\\"([0-9a-f]+)\\\"").find(html)?.groupValues?.get(1).orEmpty()
        val json = runCatching { String(decodeHex(encoded), Charsets.UTF_8) }.getOrDefault("[]")
        val entries = runCatching { catalogJson.decodeFromString<List<CatalogEntry>>(json) }.getOrDefault(emptyList())
        val queryTerms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val matches = entries.asSequence()
            .filter { item -> queryTerms.all { term -> item.title.contains(term, ignoreCase = true) } }
            .toList()
        val pageSize = 50
        val results = matches.asSequence()
            .drop((page - 1).coerceAtLeast(0) * pageSize)
            .take(pageSize)
            .map { item ->
                SAnime.create().apply {
                    title = item.title
                    setUrlWithoutDomain(item.url)
                    thumbnail_url = item.image
                    status = if (item.air == 1) SAnime.ONGOING else SAnime.UNKNOWN
                }
            }
            .toList()
        return eu.kanade.tachiyomi.animesource.model.AnimesPage(results, matches.size > page * pageSize)
    }

    private fun decodeHex(value: String): ByteArray {
        if (value.length % 2 != 0) return ByteArray(0)
        fun nibble(char: Char): Int = when (char) {
            in '0'..'9' -> char.code - '0'.code
            in 'a'..'f' -> char.code - 'a'.code + 10
            in 'A'..'F' -> char.code - 'A'.code + 10
            else -> -1
        }
        if (value.any { nibble(it) < 0 }) return ByteArray(0)
        return ByteArray(value.length / 2) { index ->
            val high = nibble(value[index * 2])
            val low = nibble(value[index * 2 + 1])
            ((high shl 4) or low).toByte()
        }
    }

    @Serializable
    private data class CatalogEntry(
        @kotlinx.serialization.SerialName("t") val title: String,
        @kotlinx.serialization.SerialName("url") val url: String,
        @kotlinx.serialization.SerialName("img") val image: String = "",
        @kotlinx.serialization.SerialName("air") val air: Int = 0,
    )

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
