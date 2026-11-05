package eu.kanade.tachiyomi.extension.es.manhwashot

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class ManhwaShot : KeiSource() {

    override val supportsLatest = true

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchList(page, "")

    // ============================== Latest ===============================
    // La portada solo muestra las últimas 15 actualizaciones (sin más páginas)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get(baseUrl, Fetch.DOCUMENT).asJsoup()
        return MangasPage(parseCards(document, ".series-grid .s-card"), false)
    }

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage =
        fetchList(page, query)

    private suspend fun fetchList(page: Int, query: String): MangasPage {
        val path = if (page > 1) "$baseUrl/explorar/page/$page/" else "$baseUrl/explorar/"
        val url = path.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
        }.build().toString()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()

        var mangas = parseCards(document, ".s-card")
        if (mangas.isEmpty()) {
            // Plan B por si el catálogo usa otro diseño de tarjetas
            mangas = document.select("a[href*=/manga/]:has(img)").mapNotNull { a ->
                val href = a.attr("abs:href")
                val segments = href.toHttpUrl().pathSegments.filter { it.isNotEmpty() }
                if (segments.size != 2 || segments[0] != "manga") return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    val img = a.selectFirst("img")
                    title = img?.attr("alt").orEmpty().ifBlank { a.text() }
                    thumbnail_url = img?.attr("abs:src")
                }
            }.distinctBy { it.url }
        }

        val hasNextLink = document.selectFirst("a[href*=/page/${page + 1}/]") != null
        val noPager = document.selectFirst("a[href*=/page/]") == null
        return MangasPage(mangas, hasNextLink || (noPager && mangas.isNotEmpty()))
    }

    private fun parseCards(document: Document, selector: String): List<SManga> =
        document.select(selector).mapNotNull { card ->
            val link = card.selectFirst("a.s-card-title")
                ?: card.selectFirst("a[href*=/manga/]")
                ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.text().ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
                thumbnail_url = card.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

    // ======================= Details + Chapters ==========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()

        manga.apply {
            title = document.selectFirst("h1.series-title")?.text().orEmpty().ifBlank { manga.title }
            thumbnail_url = document.selectFirst(".series-cover img")?.attr("abs:src")
            genre = document.select(".series-genres .genre-tag").joinToString { it.text() }

            val descs = document.select(".series-desc").map { it.text().trim() }
                .filter { it.isNotBlank() && !it.startsWith("¿Buscas leer") }
            val alt = document.selectFirst(".series-alt-titles")?.text().orEmpty()
            description = buildString {
                append(descs.joinToString("\n\n"))
                if (alt.isNotBlank()) append("\n\n").append(alt)
            }.trim()

            val badge = document.select(".series-badges .badge-pill").text().lowercase()
            status = when {
                "emisi" in badge || "curso" in badge -> SManga.ONGOING
                "finaliz" in badge || "complet" in badge -> SManga.COMPLETED
                "paus" in badge || "hiato" in badge || "hiatus" in badge -> SManga.ON_HIATUS
                "cancel" in badge || "abandon" in badge -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }

        // La web ya los lista del más nuevo al más viejo
        val chapterList = document.select("a.ch-row").map { row ->
            SChapter.create().apply {
                setUrlWithoutDomain(row.attr("abs:href"))
                val num = row.selectFirst(".ch-num")?.text().orEmpty()
                    .replace("Capítulo", "", ignoreCase = true).trim()
                name = "Capítulo $num"
                chapter_number = num.toFloatOrNull() ?: -1f
                date_upload = parseRelativeDate(row.select("span").lastOrNull()?.text().orEmpty())
            }
        }

        return SMangaUpdate(manga, chapterList)
    }

    private val relativeRegex = Regex("""hace\s+(\d+)\s*(min|mes|sem|h|d|a)""", RegexOption.IGNORE_CASE)

    private fun parseRelativeDate(text: String): Long {
        val match = relativeRegex.find(text) ?: return 0L
        val n = match.groupValues[1].toLongOrNull() ?: return 0L
        val minute = 60_000L
        val millis = when (match.groupValues[2].lowercase()) {
            "min" -> n * minute
            "h" -> n * 60 * minute
            "d" -> n * 24 * 60 * minute
            "sem" -> n * 7 * 24 * 60 * minute
            "mes" -> n * 30 * 24 * 60 * minute
            "a" -> n * 365 * 24 * 60 * minute
            else -> 0L
        }
        return System.currentTimeMillis() - millis
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get("$baseUrl${chapter.url}", Fetch.DOCUMENT).asJsoup()

        var urls = document.select("img[src*=/WP-manga/data/]").map { it.attr("abs:src") }

        if (urls.isEmpty()) {
            // Plan B: buscar las imágenes dentro del código de la página
            val html = document.html().replace("\\/", "/")
            urls = Regex("""https?://[^"'\\\s]+/WP-manga/data/[^"'\\\s]+?\.(?:webp|jpg|jpeg|png)""")
                .findAll(html).map { it.value }.toList()
        }

        val numberRegex = Regex("""/(\d+)\.\w+$""")
        return urls.distinct()
            .sortedBy { numberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
            .mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }
}

