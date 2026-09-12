package com.halovoid.bunorisources.crawler

import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.ExtensionMetadata
import com.halovoid.bunori.extension.api.models.ListingDto
import com.halovoid.bunori.extension.api.models.NovelDto
import com.halovoid.bunori.extension.api.models.SearchResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Extension source implementation for NovelFull (novelfull.com).
 */
class NovelFull(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "novelfull",
        name = "Novel Full",
        version = "1.0.1",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://novelfull.com",
        iconUrl = "https://novelfull.com/favicon.ico",
        webviewNeeded = false,
        runnerConcurrency = 3,
        runnerCooldown = 1000L,
        maxAttempts = 3
    )

    override suspend fun search(query: String, page: Int): List<SearchResultDto> {
        val searchUrl = "${metadata.baseUrl}/search?keyword=${query.replace(" ", "+")}&page=$page"
        val doc = http.document(searchUrl) ?: return emptyList()

        return doc.select(".col-truyen-main .list-truyen .row").mapNotNull { el ->
            val link = el.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
            val title = link.text().trim()
            val url = link.attr("abs:href")
            val coverUrl = el.selectFirst("img.cover")?.attr("abs:src")
            val author = el.selectFirst(".author")?.text()?.trim()

            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            SearchResultDto(
                url = url,
                title = title,
                coverUrl = coverUrl?.ifEmpty { null },
                author = author?.ifEmpty { null }
            )
        }
    }

    override suspend fun getNovelDetails(novelUrl: String): NovelDto {
        val doc = http.document(novelUrl) ?: throw IOException("Failed to fetch novel metadata from $novelUrl")

        val title = doc.select("h3.title").first()?.text()?.trim() ?: ""
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

    private suspend fun getChapterList(novelUrl: String, initialDoc: org.jsoup.nodes.Document): List<ChapterDto> {
        val pagination = initialDoc.select("ul.pagination")
        val totalPages = if (pagination.isNotEmpty()) {
            val lastPageLink = pagination.select("li.last a").attr("href")
            if (lastPageLink.isNotEmpty()) {
                lastPageLink.substringAfter("page=").toIntOrNull() ?: 1
            } else {
                pagination.select("li a").mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: 1
            }
        } else {
            1
        }

        fun parseChaptersFromDoc(d: org.jsoup.nodes.Document): List<ChapterDto> {
            return d.select("#list-chapter .row a").map { element ->
                ChapterDto(
                    url = element.attr("abs:href"),
                    title = element.text().trim()
                )
            }
        }

        val chapters = mutableListOf<ChapterDto>()
        chapters.addAll(parseChaptersFromDoc(initialDoc))

        if (totalPages > 1) {
            val remainingPages = coroutineScope {
                (2..totalPages).map { page ->
                    async(Dispatchers.IO) {
                        val pageDoc = http.document("$novelUrl?page=$page")
                        if (pageDoc != null) parseChaptersFromDoc(pageDoc) else emptyList()
                    }
                }.awaitAll().flatten()
            }
            chapters.addAll(remainingPages)
        }

        return chapters.distinctBy { it.url }.mapIndexed { index, chapter ->
            chapter.copy(index = index + 1)
        }
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val doc = http.document(chapterUrl) ?: return null
        val content = http.cleanHtml(doc, "#chapter-content")

        return Jsoup.parse(content).apply {
            select("script, style, ins, .adsbygoogle, .ads-holder, div[align='center']").remove()
        }.body().html().trim()
    }

    override fun getListings(): List<ListingDto> {
        return listOf(
            ListingDto("latest-release-novel", "Latest Releases"),
            ListingDto("hot-novel", "Hot Novels"),
            ListingDto("completed-novel", "Completed Novels")
        )
    }

    override suspend fun getListingNovels(listingId: String, page: Int): List<SearchResultDto> {
        val listingUrl = "${metadata.baseUrl}/$listingId?page=$page"
        val doc = http.document(listingUrl) ?: return emptyList()

        return doc.select(".col-truyen-main .list-truyen .row").mapNotNull { el ->
            val link = el.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
            val title = link.text().trim()
            val url = link.attr("abs:href")
            val coverUrl = el.selectFirst("img.cover")?.attr("abs:src")
            val author = el.selectFirst(".author")?.text()?.trim()

            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            SearchResultDto(
                url = url,
                title = title,
                coverUrl = coverUrl?.ifEmpty { null },
                author = author?.ifEmpty { null }
            )
        }
    }
}
