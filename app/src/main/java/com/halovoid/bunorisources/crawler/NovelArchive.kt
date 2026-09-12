package com.halovoid.bunorisources.crawler

import android.util.Log
import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.ExtensionMetadata
import com.halovoid.bunori.extension.api.models.NovelDto
import com.halovoid.bunori.extension.api.models.SearchResultDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.IOException

/**
 * Extension source implementation for Novel Archive (novelarchive.cc).
 */
class NovelArchive(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "novelarchive",
        name = "Novel Archive",
        version = "1.0.0",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://novelarchive.cc",
        iconUrl = "https://novelarchive.cc/favicon.ico",
        webviewNeeded = false,
        runnerConcurrency = 3,
        runnerCooldown = 1000L,
        maxAttempts = 3
    )

    override suspend fun search(query: String, page: Int): List<SearchResultDto> {
        val searchUrl = "${metadata.baseUrl}/api/novels?search=${query.replace(" ", "+")}&fuzzy=1"
        val jsonString = http.fetch(searchUrl) ?: return emptyList()

        val json = JSONObject(jsonString)
        val novelsArray = json.optJSONArray("novels") ?: return emptyList()
        val results = mutableListOf<SearchResultDto>()

        for (i in 0 until novelsArray.length()) {
            val novelObj = novelsArray.getJSONObject(i)
            val id = novelObj.getString("id")
            val title = novelObj.getString("title")
            val author = novelObj.optString("author")
            val coverUrl = novelObj.optString("cover_url").let {
                if (it.startsWith("/")) "${metadata.baseUrl}$it" else it
            }

            results.add(
                SearchResultDto(
                    url = "${metadata.baseUrl}/novel?id=$id",
                    title = title,
                    coverUrl = coverUrl.ifEmpty { null },
                    author = author.ifEmpty { null }
                )
            )
        }
        return results
    }

    override suspend fun getNovelDetails(novelUrl: String): NovelDto {
        val novelId = extractId(novelUrl) ?: throw IOException("Could not extract novel ID from $novelUrl")
        val apiUrl = "${metadata.baseUrl}/api/novels/$novelId"

        val jsonString = http.fetch(apiUrl) ?: throw IOException("Failed to fetch novel metadata from $apiUrl")
        val json = JSONObject(jsonString).getJSONObject("novel")
        val title = json.optString("title")
        val author = json.optString("author")
        val coverUrl = json.optString("cover_url").let {
            if (it.startsWith("/")) "${metadata.baseUrl}$it" else it
        }
        val description = json.optString("description")

        val chapters = getChapterList(novelId, novelUrl, json)

        return NovelDto(
            url = novelUrl,
            title = title,
            author = author.ifEmpty { null },
            coverUrl = coverUrl.ifEmpty { null },
            description = description.ifEmpty { null },
            chapters = chapters
        )
    }

    private suspend fun getChapterList(
        novelId: String,
        novelUrl: String,
        novelJson: JSONObject
    ): List<ChapterDto> {
        val chapters = mutableListOf<ChapterDto>()

        // 1. Primary source
        val chapterNames = novelJson.optJSONArray("chapter_names")
        if (chapterNames != null && chapterNames.length() > 0) {
            for (i in 0 until chapterNames.length()) {
                val number = i + 1
                val rawTitle = chapterNames.optString(i, "")
                val title = if (rawTitle.isBlank()) "Chapter $number" else rawTitle
                chapters.add(
                    ChapterDto(
                        url = "${metadata.baseUrl}/api/novels/$novelId/chapters/$number",
                        title = title,
                        index = number,
                        scanlation = metadata.name
                    )
                )
            }
        } else {
            val totalChapters = novelJson.optString("total_chapters", "0").toIntOrNull() ?: 0
            if (totalChapters > 0) {
                for (number in 1..totalChapters) {
                    chapters.add(
                        ChapterDto(
                            url = "${metadata.baseUrl}/api/novels/$novelId/chapters/$number",
                            title = "Chapter $number",
                            index = number,
                            scanlation = metadata.name
                        )
                    )
                }
            }
        }

        // 2. External sources
        val sourcesToFetch = mutableListOf<Pair<String, String>>()
        val fetchedSourceIds = mutableSetOf<String>()

        val sourcesApiUrl = "${metadata.baseUrl}/api/novels/$novelId/sources"
        val sourcesJsonString = http.fetch(sourcesApiUrl)
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
                            sourcesToFetch.add(srcId to srcLabel)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(metadata.name, "Failed to parse sources for novel $novelId", e)
            }
        }

        if (sourcesToFetch.isNotEmpty()) {
            val externalChapters = coroutineScope {
                sourcesToFetch.map { (srcId, srcLabel) ->
                    async(Dispatchers.IO) {
                        fetchExternalSourceChapters(novelId, srcId, srcLabel)
                    }
                }.awaitAll().flatten()
            }
            chapters.addAll(externalChapters)
        }

        return chapters
    }

    private suspend fun fetchExternalSourceChapters(
        novelId: String,
        sourceId: String,
        sourceLabel: String
    ): List<ChapterDto> {
        val chaptersApiUrl = "${metadata.baseUrl}/api/novels/$novelId/sources/$sourceId/chapters"
        val chaptersJsonString = http.fetch(chaptersApiUrl) ?: return emptyList()

        val result = mutableListOf<ChapterDto>()
        try {
            val chaptersJson = JSONObject(chaptersJsonString)
            val chaptersArray = chaptersJson.optJSONArray("chapters") ?: return emptyList()
            for (i in 0 until chaptersArray.length()) {
                val chapterObj = chaptersArray.getJSONObject(i)
                val number = chapterObj.getInt("number")
                val title = chapterObj.optString("title", "Chapter $number")
                result.add(
                    ChapterDto(
                        url = "${metadata.baseUrl}/api/novels/$novelId/sources/$sourceId/chapters/$number",
                        title = title,
                        index = number,
                        scanlation = sourceLabel
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(metadata.name, "Failed to parse chapter list for source $sourceId", e)
        }
        return result
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val jsonString = http.fetch(chapterUrl) ?: return null
        val json = JSONObject(jsonString)

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
            .replace("src=\"/", "src=\"${metadata.baseUrl}/")
            .replace("src='/", "src='${metadata.baseUrl}/")
    }

    private fun formatTextContent(text: String): String {
        return text.split("\n")
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${it.trim()}</p>" }
            .replace("src=\"/", "src=\"${metadata.baseUrl}/")
            .replace("src='/", "src='${metadata.baseUrl}/")
    }

    private fun extractId(url: String): String? {
        if (url.contains("id=")) {
            return url.substringAfter("id=").substringBefore("&")
        }
        val pathSegments = url.split("/")
        if (pathSegments.lastOrNull()?.length == 24) {
            return pathSegments.last()
        }
        return null
    }
}
