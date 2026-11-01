package eu.kanade.tachiyomi.animeextension.pl.animewatch

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeWatch : ParsedAnimeHttpSource() {
    override val name = "AnimeWatch"
    override val baseUrl = "https://animewatch.pl"
    override val lang = "pl"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/anime" else "$baseUrl/anime/page/$page/", headers)
    override fun popularAnimeSelector(): String = "article.bs"
    override fun popularAnimeFromElement(element: Element): SAnime = animeFromCard(element)
    override fun popularAnimeNextPageSelector(): String = ".hpage a.r, .pagination a.next"
    override fun latestUpdatesRequest(page: Int): Request = GET(if (page == 1) baseUrl else "$baseUrl/page/$page/", headers)
    override fun latestUpdatesSelector(): String = ".listupd article.bs"
    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromCard(element)
    override fun latestUpdatesNextPageSelector(): String = ".hpage a.r, .pagination a.next"
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/page/$page/?s=${query.urlEncoded()}", headers)
    override fun searchAnimeSelector(): String = "article.bs"
    override fun searchAnimeFromElement(element: Element): SAnime = animeFromCard(element)
    override fun searchAnimeNextPageSelector(): String = ".hpage a.r, .pagination a.next"

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text().orEmpty()
        description = document.selectFirst(".entry-content, .desc, .synp")?.text()
        thumbnail_url = document.selectFirst(".info img, .thumb img")?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
        genre = document.select("a[href*='/genres/']").joinToString { it.text() }
        status = when {
            document.text().contains("Emitowane", ignoreCase = true) -> SAnime.ONGOING
            document.text().contains("Zakończone", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListSelector(): String = ".singlepisode ul li a, .eplister ul li a"
    override fun episodeFromElement(element: Element): SEpisode {
        val title = element.text()
        val number = Regex("(?i)odc(?:inek)?\\s*(\\d+(?:[.,]\\d+)?)")
            .find(title)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: 0f
        return SEpisode.create().apply {
            name = title
            episode_number = number
            url = element.absUrl("href")
        }
    }

    override fun videoListSelector(): String = "iframe[src]"
    override fun videoFromElement(element: Element): Video {
        val url = element.absUrl("src")
        return Video(url.substringAfter("//").substringBefore('/'), url, url)
    }
    override fun videoUrlParse(document: Document): String = document.selectFirst("video source[src], video[src]")?.absUrl("src").orEmpty()

    private fun animeFromCard(element: Element): SAnime {
        val link = element.selectFirst("a[itemprop=url], .bsx a[href], a[href]")
        val image = element.selectFirst("img")
        return SAnime.create().apply {
            title = element.selectFirst(".tt, .data h3, h3")?.text()
                ?: link?.attr("title")?.ifBlank { link.text() }?.orEmpty()
                ?: image?.attr("alt").orEmpty()
            setUrlWithoutDomain(link?.absUrl("href").orEmpty())
            thumbnail_url = image?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
        }
    }

    private fun String.urlEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
