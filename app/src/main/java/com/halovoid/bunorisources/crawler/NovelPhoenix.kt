package com.halovoid.bunorisources.crawler

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
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Extension source implementation for Novel Phoenix (novelphoenix.com).
 */
class NovelPhoenix(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "novelphoenix",
        name = "Novel Phoenix",
        version = "1.0.0",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://novelphoenix.com",
        iconUrl = "https://novelphoenix.com/favicon.ico",
        webviewNeeded = false,
        runnerConcurrency = 3,
        runnerCooldown = 1000L,
        maxAttempts = 3
    )

    override suspend fun search(query: String, page: Int): List<SearchResultDto> {
        val searchUrl = "${metadata.baseUrl}/ajax/searchLive?keyword=${query.replace(" ", "%20")}&type=title"
        val response = http.fetch(searchUrl) ?: return emptyList()

        return try {
            val json = JSONObject(response)
            val dataArray = json.optJSONArray("data") ?: return emptyList()
            val novels = mutableListOf<SearchResultDto>()

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.getJSONObject(i)
                val title = obj.optString("title")
                val slug = obj.optString("slug")
                val image = obj.optString("image")
                val coverUrl = if (image.isNotEmpty()) "${metadata.baseUrl}/$image" else null

                if (title.isNotEmpty() && slug.isNotEmpty()) {
                    novels.add(
                        SearchResultDto(
                            url = "${metadata.baseUrl}/novel/$slug",
                            title = title,
                            coverUrl = coverUrl
                        )
                    )
                }
            }
            novels
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getNovelDetails(novelUrl: String): NovelDto {
        val cleanNovelUrl = novelUrl.removeSuffix("/")
        val doc = http.document(cleanNovelUrl) ?: throw IOException("Failed to fetch novel metadata from $novelUrl")

        val title = doc.select("h1.novel-title").text().trim()
        val author = doc.select(".author a[itemprop='author'], .author span[itemprop='author']").text().trim()
        val coverUrl = doc.select(".fixed-img figure.cover img").attr("abs:src")
        val description = doc.select(".summary .content").clone().apply {
            select(".expand").remove()
        }.text().trim()

        val chapters = getChapterList(cleanNovelUrl)

        return NovelDto(
            url = cleanNovelUrl,
            title = title,
            author = author.ifEmpty { null },
            coverUrl = coverUrl.ifEmpty { null },
            description = description.ifEmpty { null },
            chapters = chapters
        )
    }

    private suspend fun getChapterList(cleanNovelUrl: String): List<ChapterDto> {
        val chapterListUrl = "$cleanNovelUrl/chapters"
        val chaptersDoc = http.document(chapterListUrl) ?: throw IOException("Failed to fetch chapter list from $chapterListUrl")

        var maxPage = 1
        chaptersDoc.select("ul.pagination li.page-item a.page-link").forEach { element ->
            val pageUrl = element.attr("href")
            val pageNum = pageUrl.substringAfter("page=").substringBefore("&").toIntOrNull()
            if (pageNum != null && pageNum > maxPage) {
                maxPage = pageNum
            }
        }

        fun parseChapters(d: org.jsoup.nodes.Document): List<ChapterDto> {
            return d.select("ul.chapter-list li a").map { element ->
                val chapUrl = element.attr("abs:href")
                val chapTitle = element.select("strong.chapter-title").text().ifEmpty {
                    element.text().replace(element.select(".chapter-no").text(), "").trim()
                }
                ChapterDto(
                    url = chapUrl,
                    title = chapTitle
                )
            }
        }

        val chapters = mutableListOf<ChapterDto>()
        chapters.addAll(parseChapters(chaptersDoc))

        if (maxPage > 1) {
            val remainingPages = coroutineScope {
                (2..maxPage).map { i ->
                    async(Dispatchers.IO) {
                        val pageDoc = http.document("$chapterListUrl?page=$i")
                        if (pageDoc != null) parseChapters(pageDoc) else emptyList()
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
        val content = http.cleanHtml(
            doc,
            "#content",
            listOf("script", "style", "iframe", ".nf-ads", ".box-notice", ".box-notification")
        )
        return Jsoup.parse(content).body().html().trim()
    }
}
