package com.halovoid.bunorisources.crawler

import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.ExtensionMetadata
import com.halovoid.bunori.extension.api.models.NovelDto
import com.halovoid.bunori.extension.api.models.SearchResultDto
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Extension source implementation for Novgo (novgo.net).
 */
class Novgo(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "novgo",
        name = "Nov Go",
        version = "1.0.1",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://novgo.net",
        iconUrl = "https://novgo.net/favicon.ico",
        webviewNeeded = false,
        runnerConcurrency = 2,
        runnerCooldown = 1000L,
        maxAttempts = 3
    )

    override suspend fun search(query: String, page: Int): List<SearchResultDto> {
        val searchUrl = "${metadata.baseUrl}/search?keyword=${query.replace(" ", "+")}&page=$page"
        val doc = http.document(searchUrl) ?: return emptyList()

        return doc.select(".col-truyen-main .list-truyen .row").mapNotNull { element ->
            val titleElement = element.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
            val title = titleElement.text()
            val url = titleElement.attr("abs:href")
            val coverUrl = element.select("img.cover").attr("abs:src")
            val author = element.select(".author").text().trim()

            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            SearchResultDto(
                url = url,
                title = title,
                coverUrl = coverUrl.ifEmpty { null },
                author = author.ifEmpty { null }
            )
        }
    }

    override suspend fun getNovelDetails(novelUrl: String): NovelDto {
        val doc = http.document(novelUrl) ?: throw IOException("Failed to fetch novel metadata from $novelUrl")

        val title = doc.select("h1.title").first()?.text() ?: doc.select("h3.title").first()?.text() ?: ""
        val author = doc.select(".info div:contains(Author) a").text().trim()
        val coverUrl = doc.select(".book img").attr("abs:src")
        val description = doc.select(".desc-text").text().trim()

        val chapters = getChapterList(novelUrl, doc)

        return NovelDto(
            url = novelUrl,
            title = title,
            author = author.ifEmpty { null },
            coverUrl = coverUrl.ifEmpty { null },
            description = description.ifEmpty { null },
            chapters = chapters
        )
    }

    private suspend fun getChapterList(novelUrl: String, doc: org.jsoup.nodes.Document): List<ChapterDto> {
        val novelId = doc.select("#rating[data-novel-id], [data-novel-id], #novel_id").attr("data-novel-id").ifEmpty {
            doc.select("input#novel-id, input#novel_id").attr("value")
        }

        if (novelId.isNotEmpty()) {
            val ajaxChapters = fetchChaptersViaAjax(novelId)
            if (ajaxChapters.isNotEmpty()) {
                return ajaxChapters
            }
        }

        return fetchChaptersViaHtml(novelUrl, doc)
    }

    private suspend fun fetchChaptersViaAjax(novelId: String): List<ChapterDto> {
        val ajaxUrl = "${metadata.baseUrl}/ajax-chapter-option?novelId=$novelId"
        val response = http.fetch(ajaxUrl) ?: return emptyList()

        val doc = Jsoup.parse(response, metadata.baseUrl)
        val elements = doc.select("option[value], a[href]")
        if (elements.isEmpty()) return emptyList()

        val chapters = mutableListOf<ChapterDto>()
        for (i in 0 until elements.size) {
            val el = elements[i]
            val path = el.attr("value").ifEmpty { el.attr("href") }
            if (path.isBlank()) continue
            val fullUrl = if (path.startsWith("http")) path else "${metadata.baseUrl}/${path.removePrefix("/")}"
            val title = el.text().trim().ifEmpty { el.attr("title").trim() }
            chapters.add(
                ChapterDto(
                    url = fullUrl,
                    title = title.ifEmpty { "Chapter ${i + 1}" },
                    index = i + 1
                )
            )
        }
        return chapters
    }

    private suspend fun fetchChaptersViaHtml(novelUrl: String, doc: org.jsoup.nodes.Document): List<ChapterDto> {
        val totalPages = doc.select("input#total-page").attr("value").toIntOrNull() ?: 1

        fun parseChaptersFromDoc(d: org.jsoup.nodes.Document): List<ChapterDto> {
            return d.select("#list-chapter .list-chapter li a").map { element ->
                ChapterDto(
                    url = element.attr("abs:href"),
                    title = element.text()
                )
            }
        }

        val chapters = mutableListOf<ChapterDto>()
        chapters.addAll(parseChaptersFromDoc(doc))

        if (totalPages > 1) {
            for (page in 2..totalPages) {
                val pageUrl = if (novelUrl.contains("?")) "$novelUrl&page=$page" else "$novelUrl?page=$page"
                val pageDoc = http.document(pageUrl)
                if (pageDoc != null) chapters.addAll(parseChaptersFromDoc(pageDoc))
            }
        }

        return chapters.distinctBy { it.url }.mapIndexed { index, chapter ->
            chapter.copy(index = index + 1)
        }
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val doc = http.document(chapterUrl) ?: return null

        val content = http.cleanHtml(doc, "#chapter-content")

        return Jsoup.parse(content).apply {
            select("iframe, .ads, .adsbox, script, .chapter-nav, hr").remove()
        }.body().html().trim()
    }
}
