package eu.kanade.tachiyomi.animeextension.pl.animeodcinki

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeOdcinki : ParsedAnimeHttpSource() {
    override val name = "Anime-Odcinki"
    override val baseUrl = "https://anime-odcinki.pl"
    override val lang = "pl"
    override val supportsLatest = true
    private var pendingSearchQuery = ""

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-lista/", headers)
    override fun popularAnimeParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = parseCatalog(response, "")
    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()
    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun popularAnimeNextPageSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)
    override fun latestUpdatesSelector(): String = "#vod-recent-anime-list a[href*='/anime/']"
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromElement(element)
    override fun latestUpdatesNextPageSelector(): String = "#vod-recent-anime-btn"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        pendingSearchQuery = query
        return GET("$baseUrl/anime-lista/", headers)
    }
    override fun searchAnimeParse(response: okhttp3.Response): eu.kanade.tachiyomi.animesource.model.AnimesPage = parseCatalog(response, pendingSearchQuery)
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

    override fun videoListSelector(): String = "#vod-iframe[src], .vod-player-box iframe[src]"
    override fun videoFromElement(element: Element): Video {
        val url = element.absUrl("src")
        return Video(url.substringAfter("//").substringBefore('/'), url, url)
    }
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

    private fun parseCatalog(response: okhttp3.Response, query: String): eu.kanade.tachiyomi.animesource.model.AnimesPage {
        val html = response.body.string()
        val encoded = Regex("const hexData = \\\"([0-9a-f]+)\\\"").find(html)?.groupValues?.get(1).orEmpty()
        val json = runCatching { String(decodeHex(encoded), Charsets.UTF_8) }.getOrDefault("[]")
        val entries = runCatching { Json.decodeFromString<List<CatalogEntry>>(json) }.getOrDefault(emptyList())
        val queryTerms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        val results = entries.asSequence()
            .filter { item -> queryTerms.all { term -> item.title.contains(term, ignoreCase = true) } }
            .map { item ->
                SAnime.create().apply {
                    title = item.title
                    setUrlWithoutDomain(item.url)
                    thumbnail_url = item.image
                    status = if (item.air == 1) SAnime.ONGOING else SAnime.UNKNOWN
                }
            }
            .toList()
        return eu.kanade.tachiyomi.animesource.model.AnimesPage(results, false)
    }

    private fun decodeHex(value: String): ByteArray {
        if (value.length % 2 != 0) return ByteArray(0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
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
