package com.halovoid.lncrawlersources.crawler

import android.util.Log
import com.halovoid.lncrawler.api.core.config.CrawlerConfig
import com.halovoid.lncrawler.api.core.crawler.Crawler
import com.halovoid.lncrawler.domain.models.Chapter
import com.halovoid.lncrawler.domain.models.Novel
import org.json.JSONObject
import java.io.IOException

/**
 * Crawler implementation for Novel Archive (novelarchive.cc).
 * Collects chapters across all available scanlation sources (Novel Archive primary database
 * and external mirror sources), correctly setting scanlationSource and indexing per source.
 */
class NovelArchive : Crawler() {
    override val name: String = "Novel Archive"
    override val baseUrl: String = "https://novelarchive.cc"
    override val webviewNeeded: Boolean = false

    override val config: CrawlerConfig
        get() = _config ?: CrawlerConfig(
            userFolderLocation = "",
            maxAttempts = 3,
            runnerConcurrency = 1 // Slower scraping as requested
        )

    override val chapterPerVolume: Int = 100

    override fun canHandle(url: String): Boolean {
        return url.contains("novelarchive.cc")
    }

    override suspend fun getNovelMetadata(novelUrl: String): Novel {
        val novelId = extractId(novelUrl) ?: throw IOException("Could not extract novel ID from $novelUrl")
        val apiUrl = "$baseUrl/api/novels/$novelId"
        
        val jsonString = fetchHtml(apiUrl) ?: throw IOException("Failed to fetch novel metadata from $apiUrl")
        Log.i(name, "Scraping novel metadata: $apiUrl")

        val json = JSONObject(jsonString).getJSONObject("novel")
        val title = json.optString("title")
        val author = json.optString("author")
        val coverUrl = json.optString("cover_url").let { if (it.startsWith("/")) "$baseUrl$it" else it }
        val description = json.optString("description")

        return Novel(
            url = novelUrl,
            title = title,
            author = author,
            coverUrl = coverUrl,
            description = description,
            chapters = emptyList(),
            crawlerName = name,
            alternativeNames = json.optJSONArray("associated_names")?.let { arr ->
                List(arr.length()) { arr.getString(it) }.joinToString(", ")
            }
        )
    }

    override suspend fun getChapterList(novelUrl: String): List<Chapter> {
        val novelId = extractId(novelUrl) ?: throw IOException("Could not extract novel ID from $novelUrl")
        val novelApiUrl = "$baseUrl/api/novels/$novelId"
        
        val novelJsonString = fetchHtml(novelApiUrl) ?: throw IOException("Failed to fetch novel metadata from $novelApiUrl")
        val novelJson = JSONObject(novelJsonString).getJSONObject("novel")
        
        val chapters = mutableListOf<Chapter>()

        // 1. Primary source: Novel Archive's main database (chapter_names or total_chapters)
        val chapterNames = novelJson.optJSONArray("chapter_names")
        if (chapterNames != null && chapterNames.length() > 0) {
            for (i in 0 until chapterNames.length()) {
                val number = i + 1
                val rawTitle = chapterNames.optString(i, "")
                val title = if (rawTitle.isBlank()) "Chapter $number" else rawTitle
                chapters.add(
                    Chapter(
                        id = 0,
                        url = "$baseUrl/api/novels/$novelId/chapters/$number",
                        novelUrl = novelUrl,
                        title = title,
                        index = number,
                        volumeId = "${novelUrl}_vol_${(i / chapterPerVolume) + 1}",
                        fileLocation = null
                    ).apply { scanlationSource = name }
                )
            }
        } else {
            val totalChaptersStr = novelJson.optString("total_chapters", "0")
            val totalChapters = totalChaptersStr.toIntOrNull() ?: 0
            if (totalChapters > 0) {
                for (number in 1..totalChapters) {
                    chapters.add(
                        Chapter(
                            id = 0,
                            url = "$baseUrl/api/novels/$novelId/chapters/$number",
                            novelUrl = novelUrl,
                            title = "Chapter $number",
                            index = number,
                            volumeId = "${novelUrl}_vol_${((number - 1) / chapterPerVolume) + 1}",
                            fileLocation = null
                        ).apply { scanlationSource = name }
                    )
                }
            }
        }

        // 2. External sources from /api/novels/{id}/sources
        val fetchedSourceIds = mutableSetOf<String>()

        val sourcesApiUrl = "$baseUrl/api/novels/$novelId/sources"
        val sourcesJsonString = fetchHtml(sourcesApiUrl)
        if (sourcesJsonString != null) {
            try {
                val sourcesJson = JSONObject(sourcesJsonString)
                val sourcesArray = sourcesJson.optJSONArray("sources")
                if (sourcesArray != null) {
                    for (i in 0 until sourcesArray.length()) {
                        val srcObj = sourcesArray.getJSONObject(i)
                        val srcId = srcObj.optString("id").trim()
                        val srcLabel = srcObj.optString("label").trim().ifEmpty { srcId }
                        if (srcId.isNotEmpty() && !fetchedSourceIds.contains(srcId)) {
                            fetchedSourceIds.add(srcId)
                            fetchExternalSourceChapters(novelId, novelUrl, srcId, srcLabel, chapters)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(name, "Failed to parse sources for novel $novelId", e)
            }
        }

        // Check preferred_source or common fallbacks if not yet fetched
        val preferredSource = novelJson.optString("preferred_source").trim()
        val extraFallbacks = listOf(preferredSource, "fucknovelpia", "ranobes").filter { it.isNotEmpty() }.distinct()
        for (fallback in extraFallbacks) {
            if (!fetchedSourceIds.contains(fallback)) {
                fetchedSourceIds.add(fallback)
                fetchExternalSourceChapters(novelId, novelUrl, fallback, fallback, chapters)
            }
        }

        return chapters
    }

    private suspend fun fetchExternalSourceChapters(
        novelId: String,
        novelUrl: String,
        sourceId: String,
        sourceLabel: String,
        outChapters: MutableList<Chapter>
    ) {
        val chaptersApiUrl = "$baseUrl/api/novels/$novelId/sources/$sourceId/chapters"
        val chaptersJsonString = fetchHtml(chaptersApiUrl) ?: return
        Log.i(name, "Scraping chapter list for source $sourceId: $chaptersApiUrl")

        try {
            val chaptersJson = JSONObject(chaptersJsonString)
            val chaptersArray = chaptersJson.optJSONArray("chapters") ?: return
            for (i in 0 until chaptersArray.length()) {
                val chapterObj = chaptersArray.getJSONObject(i)
                val number = chapterObj.getInt("number")
                val title = chapterObj.optString("title", "Chapter $number")
                outChapters.add(
                    Chapter(
                        id = 0,
                        url = "$baseUrl/api/novels/$novelId/sources/$sourceId/chapters/$number",
                        novelUrl = novelUrl,
                        title = title,
                        index = number, // Chapter number as index for this source
                        volumeId = "${novelUrl}_vol_${(i / chapterPerVolume) + 1}",
                        fileLocation = null
                    ).apply { scanlationSource = sourceLabel }
                )
            }
        } catch (e: Exception) {
            Log.w(name, "Failed to parse chapter list for source $sourceId", e)
        }
    }

    override suspend fun getNovelDetails(novelUrl: String): Novel {
        val metadata = getNovelMetadata(novelUrl)
        val chapters = getChapterList(novelUrl)
        return prepareNovel(metadata.copy(chapters = chapters))
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val jsonString = fetchHtml(chapterUrl) ?: return null
        Log.i(name, "Scraping chapter: $chapterUrl")

        val json = JSONObject(jsonString)

        // Handles default Novel Archive format: {"chapter": {"content": "...", "content_html": "..."}}
        if (json.has("chapter")) {
            val chapterObj = json.optJSONObject("chapter") ?: return null
            val contentHtml = chapterObj.optString("content_html")
            if (contentHtml.isNotEmpty()) {
                return formatHtmlContent(contentHtml)
            }
            val contentText = chapterObj.optString("content")
            if (contentText.isNotEmpty()) {
                return formatTextContent(contentText)
            }
        }

        // Handles external source format: {"content_html": "...", "content": "..."}
        val contentHtml = json.optString("content_html")
        if (contentHtml.isNotEmpty()) {
            return formatHtmlContent(contentHtml)
        }

        val contentText = json.optString("content")
        if (contentText.isNotEmpty()) {
            return formatTextContent(contentText)
        }

        return null
    }

    private fun formatHtmlContent(html: String): String {
        return html
            .replace("src=\"/", "src=\"$baseUrl/")
            .replace("src='/", "src='$baseUrl/")
    }

    private fun formatTextContent(text: String): String {
        return text.split("\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${it.trim()}</p>" }
            .replace("src=\"/", "src=\"$baseUrl/")
            .replace("src='/", "src='$baseUrl/")
    }

    override suspend fun getSearchResults(query: String): List<Novel> {
        val searchUrl = "$baseUrl/api/novels?search=${query.replace(" ", "+")}&fuzzy=1"
        val jsonString = fetchHtml(searchUrl) ?: return emptyList()

        val json = JSONObject(jsonString)
        val novelsArray = json.getJSONArray("novels")
        val results = mutableListOf<Novel>()

        for (i in 0 until novelsArray.length()) {
            val novelObj = novelsArray.getJSONObject(i)
            val id = novelObj.getString("id")
            val title = novelObj.getString("title")
            val author = novelObj.optString("author")
            val coverUrl = novelObj.optString("cover_url").let { if (it.startsWith("/")) "$baseUrl$it" else it }

            results.add(
                Novel(
                    url = "$baseUrl/novel?id=$id",
                    title = title,
                    author = author,
                    coverUrl = coverUrl,
                    description = novelObj.optString("description"),
                    chapters = emptyList(),
                    crawlerName = name,
                    alternativeNames = null
                )
            )
        }
        return results
    }

    private fun extractId(url: String): String? {
        // Handle https://novelarchive.cc/novel?id=6a69a7ed0a8005dd9415f190
        if (url.contains("id=")) {
            return url.substringAfter("id=").substringBefore("&")
        }
        // Handle https://novelarchive.cc/api/novels/6a69a7ed0a8005dd9415f190
        val pathSegments = url.split("/")
        if (pathSegments.lastOrNull()?.length == 24) { // Typical MongoDB ObjectId length
            return pathSegments.last()
        }
        return null
    }
}
