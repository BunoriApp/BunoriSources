package com.halovoid.lncrawlersources.crawler

import android.util.Log
import com.halovoid.lncrawler.api.core.config.CrawlerConfig
import com.halovoid.lncrawler.api.core.crawler.Crawler
import com.halovoid.lncrawler.domain.models.Chapter
import com.halovoid.lncrawler.domain.models.Novel
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Crawler implementation for NovelFull (novelfull.com).
 * This class handles the specific HTML structure of NovelFull, including
 * paginated chapter lists.
 */
class NovelFull : Crawler() {
    override val name: String = "Novel Full"
    override val baseUrl: String = "https://novelfull.com"
    override val webviewNeeded: Boolean = false

    override val config: CrawlerConfig
        get() = _config ?: CrawlerConfig(
            userFolderLocation = "",
            maxAttempts = 3,
            runnerConcurrency = 3
        )

    override val chapterPerVolume: Int = 100

    override fun canHandle(url: String): Boolean {
        return url.contains("novelfull.com")
    }

    override suspend fun getNovelMetadata(novelUrl: String): Novel {
        val doc = getDocument(novelUrl) ?: throw IOException("Failed to fetch novel metadata from $novelUrl")
        Log.i(name, "Scraping novel metadata: $novelUrl")

        val title = doc.select("h3.title").first()?.text() ?: ""
        val author = doc.select(".info div:contains(Author) a").text().trim()
        val alternativeNames = doc.select(".info div:contains(Alternative names)").text()
            .replace("Alternative names:", "", ignoreCase = true).trim()
        val coverUrl = doc.select(".book img").attr("abs:src")
        val description = doc.select(".desc-text").text().trim()

        return Novel(
            url = novelUrl,
            title = title,
            author = author,
            coverUrl = coverUrl,
            description = description,
            chapters = emptyList(),
            crawlerName = name,
            alternativeNames = alternativeNames
        )
    }

    override suspend fun getChapterList(novelUrl: String): List<Chapter> {
        val doc = getDocument(novelUrl) ?: throw IOException("Failed to fetch chapter list from $novelUrl")
        Log.i(name, "Scraping chapter list: $novelUrl")

        val chapters = mutableListOf<Chapter>()

        val pagination = doc.select("ul.pagination")
        val totalPages = if (pagination.isNotEmpty()) {
            val lastPageLink = pagination.select("li.last a").attr("href")
            if (lastPageLink.isNotEmpty()) {
                lastPageLink.substringAfter("page=").toIntOrNull() ?: 1
            } else {
                pagination.select("li a").mapNotNull { it.text().toIntOrNull() }
                    .maxOfOrNull { it } ?: 1
            }
        } else {
            1
        }

        Log.i(name, "Found $totalPages pages of chapters")

        for (page in 1..totalPages) {
            val pageUrl = if (page == 1) novelUrl else "$novelUrl?page=$page"
            val pageDoc = if (page == 1) doc else getDocument(pageUrl)
            
            pageDoc?.select("#list-chapter .row a")?.forEach { element ->
                chapters.add(
                    Chapter(
                        id = 0,
                        url = element.attr("abs:href"),
                        novelUrl = novelUrl,
                        title = element.text(),
                        index = 0,      // Recalculated below
                        volumeId = "",  // Recalculated below
                        fileLocation = null
                    )
                )
            }
        }

        // Clean up duplicate entries and set final indices
        return chapters.distinctBy { it.url }.mapIndexed { index, chapter ->
            chapter.copy(
                index = index + 1,
                volumeId = "${novelUrl}_vol_${(index / chapterPerVolume) + 1}"
            ).apply { scanlationSource = name }
        }
    }

    override suspend fun getNovelDetails(novelUrl: String): Novel {
        val metadata = getNovelMetadata(novelUrl)
        val chapters = getChapterList(novelUrl)
        return prepareNovel(metadata.copy(chapters = chapters))
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val doc = getDocument(chapterUrl) ?: return null
        Log.i(name, "Scraping chapter: $chapterUrl")

        // Use base class cleaning with site-specific selector
        val content = cleanHtml(doc, "#chapter-content")

        // Extra site-specific cleaning
        return Jsoup.parse(content).apply {
            select("a[href*='novelfull.com']").remove()
        }.body().html().trim()
    }

    /**
     * Searches for novels on NovelFull based on a query string.
     * Parses the search results page to return a list of [Novel] objects.
     *
     * @param query The search term.
     * @return A list of novels matching the query.
     */
    override suspend fun getSearchResults(query: String): List<Novel> {
        val searchUrl = "$baseUrl/search?keyword=${query.replace(" ", "+")}"
        val doc = getDocument(searchUrl) ?: return emptyList()

        // Restrict to .col-truyen-main to avoid sidebar results
        return doc.select(".col-truyen-main .list-truyen .row").mapNotNull { element ->
            val titleElement = element.selectFirst("h3.truyen-title a") ?: return@mapNotNull null
            val title = titleElement.text()
            val url = titleElement.attr("abs:href")
            val coverUrl = element.select("img.cover").attr("abs:src")
            val author = element.select(".author").text().trim()

            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            Novel(
                url = url,
                title = title,
                author = author,
                coverUrl = coverUrl,
                description = "",
                chapters = emptyList(),
                crawlerName = name,
                alternativeNames = null
            )
        }
    }
}
