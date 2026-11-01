package eu.kanade.tachiyomi.animeextension.pl.animeni

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeNi : ParsedAnimeHttpSource() {
    override val name = "AnimeNi"
    override val baseUrl = "https://animeni.pl"
    override val lang = "pl"
    override val supportsLatest = true

    override fun popularAnimeRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/", headers)

    override fun popularAnimeSelector(): String =
        "article.bs"

    override fun popularAnimeFromElement(element: Element): SAnime = animeFromCard(element)

    override fun popularAnimeNextPageSelector(): String =
        ".pagination .next, .pagination a[href*=/page/], a.next"

    override fun latestUpdatesRequest(page: Int): Request =
        GET(if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = animeFromCard(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/?s=${query.urlEncoded()}&page=$page", headers)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = animeFromCard(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.selectFirst("h1")?.text().orEmpty()
        description = document.selectFirst(".description, .wp-content, .entry-content, #info")?.text()
        thumbnail_url = document.selectFirst(".poster img, .cover img, .anime-poster img")?.let {
            it.absUrl("src").ifBlank { it.absUrl("data-src") }
        }
        genre = document.select(".genres a, .genxed a, a[href*=/genre/]")
            .joinToString { it.text() }
        status = when {
            document.text().contains("Emitowane", ignoreCase = true) -> SAnime.ONGOING
            document.text().contains("Zakończone", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListSelector(): String =
        ".episodios li, .episodes li, .episodios a, .episodes a"

    override fun episodeFromElement(element: Element): SEpisode {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")
        val title = element.selectFirst(".episodiotitle, .episode-title, .title")?.text()
            ?: element.text()
        val href = link?.absUrl("href").orEmpty()
        val number = Regex("""(?i)(?:odc(?:inek)?\.?\s*)?(\d+(?:[.,]\d+)?)""")
            .find(title)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: 0f
        return SEpisode.create().apply {
            name = title.ifBlank { "Odcinek ${number.toInt()}" }
            episode_number = number
            url = href
        }
    }

    override fun videoListSelector(): String = "iframe[src], video source[src]"

    override fun videoFromElement(element: Element): Video {
        val url = element.absUrl("src").ifBlank { element.parent()?.absUrl("src").orEmpty() }
        return Video(url.substringAfterLast('/').ifBlank { "Odtwarzacz" }, url, url)
    }

    override fun videoUrlParse(document: Document): String {
        val video = document.selectFirst("video source[src], video[src]") ?: return ""
        val sourceUrl = video.absUrl("src")
        if (sourceUrl.isNotBlank()) return sourceUrl
        return video.parent()?.absUrl("src") ?: ""
    }

    private fun animeFromCard(element: Element): SAnime {
        val link = element.selectFirst("a[href]") ?: element
        val image = element.selectFirst("img")
        return SAnime.create().apply {
            title = element.selectFirst(".data h3, h3, .title")?.text()
                ?: link.text().ifBlank { image?.attr("alt").orEmpty() }
            setUrlWithoutDomain(link.absUrl("href"))
            thumbnail_url = image?.let { it.absUrl("src").ifBlank { it.absUrl("data-src") } }
        }
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
