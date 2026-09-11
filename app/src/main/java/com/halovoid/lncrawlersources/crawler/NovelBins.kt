package com.halovoid.lncrawlersources.crawler

import android.util.Log
import com.halovoid.lncrawler.api.core.config.CrawlerConfig
import com.halovoid.lncrawler.api.core.crawler.Crawler
import com.halovoid.lncrawler.domain.models.Chapter
import com.halovoid.lncrawler.domain.models.Novel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import org.json.JSONArray
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Crawler implementation for NovelBin (novelbins.com) in the Data layer.
 * This class handles the specific HTML structure and AJAX endpoints of the site
 * using Jsoup for static parsing and custom logic for paginated chapter lists.
 */
class NovelBins : Crawler() {
    override val name: String = "Novel Bins"
    override val baseUrl: String = "https://novelbins.com"
    override val webviewNeeded: Boolean = false

    override val config: CrawlerConfig
        get() = _config ?: CrawlerConfig(
            userFolderLocation = "",
            maxAttempts = 3,
            runnerConcurrency = 4
        )

    override fun canHandle(url: String): Boolean {
        return url.contains("novelbins.com") || url.contains("novelbin.com")
    }

    override suspend fun getNovelMetadata(novelUrl: String): Novel {
        val doc = getDocument(novelUrl) ?: throw IOException("Failed to fetch novel details from $novelUrl")
        Log.i(name, "Scraping novel metadata: $novelUrl")

        val titleElement = doc.select(".novel-short-info h1").first()
        val title = titleElement?.ownText() ?: ""
        val alternativeNames = titleElement?.select("small")?.text()?.replace("<br>", "")?.trim()

        val author = doc.select(".novel-short-info p:contains(Author:)").text().replace("Author: ", "").trim()
        val coverUrl = doc.select("img.novel-photo").attr("abs:src")
        val description = doc.select(".novel-short-info p").getOrNull(7)?.text() ?: ""

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

        // Novel ID extraction for AJAX chapter list
        val permalink = novelUrl.removeSuffix("/").split("/").last()
        var novelId = permalink.split("-").lastOrNull { it.all { c -> c.isDigit() } } ?: ""

        // Fallback: extract from bookmark link
        if (novelId.isEmpty()) {
            val bookmarkLink = doc.select("a[href^='javascript:bookmark']").attr("href")
            novelId = bookmarkLink.substringAfter("'").substringBefore("'")
        }

        val tabLinks = doc.select("a.ch[data-toggle='tab']")

        val chapters = if (tabLinks.isEmpty()) {
            // Fallback for simple pages
            doc.select(".chapters .mt-card-item h3.mt-card-name a").mapIndexed { index, element ->
                Chapter(
                    id = 0,
                    url = element.attr("abs:href"),
                    novelUrl = novelUrl,
                    title = element.text(),
                    index = index,
                    fileLocation = null
                )
            }
        } else {
            // Paginated chapter lists via AJAX fetched concurrently
            coroutineScope {
                tabLinks.map { tabLink ->
                    async(Dispatchers.IO) {
                        val tabIndex = tabLink.attr("href").replace("#", "")
                        fetchChaptersViaAjax(novelId, tabIndex, permalink, novelUrl)
                    }
                }.awaitAll().flatten()
            }
        }

        // Clean up duplicate entries and set final indices
        return chapters.distinctBy { it.url }.mapIndexed { index, chapter ->
            chapter.copy(
                index = index + 1,
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

        // Use base class cleaning with site-specific selectors
        val content = cleanHtml(doc, ".reader, #chr-content, #chapter-content")

        // Extra site-specific cleaning for sharing links
        return Jsoup.parse(content).apply {
            select("a[href*='novelbin'], a[href*='facebook'], a[href*='twitter']").remove()
        }.body().html().trim()
    }

    /**
     * Searches for novels on NovelBin based on a query string.
     * Parses the search results page to return a list of [Novel] objects.
     *
     * @param query The search term.
     * @return A list of novels matching the query.
     */
    override suspend fun getSearchResults(query: String): List<Novel> {
        val searchUrl = "$baseUrl/search-results/?query=${query.replace(" ", "+")}"
        val doc = getDocument(searchUrl) ?: return emptyList()

        return doc.select(".mt-card-item").map { element ->
            val title = element.select("h3.mt-card-name").text()
            val url = element.select(".mt-card-avatar a").attr("abs:href")
            val coverStyle = element.select(".mt-card-avatar").attr("style")
            val coverUrl = coverStyle.substringAfter("url('").substringBefore("')")

            Novel(
                url = url,
                title = title,
                author = "",
                coverUrl = coverUrl,
                description = "",
                chapters = emptyList(),
                crawlerName = name,
                alternativeNames = ""
            )
        }
    }

    /**
     * Fetches chapter data from NovelBin's internal AJAX API.
     * Used because the main novel page only shows a subset of chapters.
     *
     * @param novelId Internal numeric ID used by the site.
     * @param tab The tab index or pagination identifier.
     * @param permalink The URL-friendly name of the novel.
     * @param refererUrl The URL of the novel landing page to be used as Referer.
     * @return A list of [Chapter]s fetched from the API.
     */
    private suspend fun fetchChaptersViaAjax(novelId: String, tab: String, permalink: String, refererUrl: String): List<Chapter> {
        val url = "$baseUrl/ajax/"
        val requestBody = FormBody.Builder()
            .add("action", "get_chapters")
            .add("id", novelId)
            .add("tab", tab)
            .build()

        val html = fetchHtml(
            url = url,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to refererUrl,
                "Origin" to baseUrl,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            body = requestBody
        ) ?: return emptyList()

        Log.i("AJAX", html)

        val chapters = mutableListOf<Chapter>()
        try {
            val jsonArray = JSONArray(html)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val chapterNum = obj.getString("chapter")
                val title = obj.getString("title")
                chapters.add(
                    Chapter(
                        id = 0,
                        url = "$baseUrl/novel/$permalink/chapter/$chapterNum/",
                        novelUrl = refererUrl,
                        title = title,
                        index = 0,      //Placeholder - recalculated in getChapterList
                        fileLocation = null
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error parsing AJAX response", e)
        }
        return chapters
    }
}
