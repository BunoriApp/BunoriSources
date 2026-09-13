package com.halovoid.bunorisources.crawler

import android.util.Log
import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.ExtensionMetadata
import com.halovoid.bunori.extension.api.models.ListingDto
import com.halovoid.bunori.extension.api.models.NovelDto
import com.halovoid.bunori.extension.api.models.SearchResultDto
import okhttp3.FormBody
import org.json.JSONArray
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Extension source implementation for NovelBin (novelbins.com).
 */
class NovelBins(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "novelbins",
        name = "Novel Bins",
        version = "1.0.1",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://novelbins.com",
        iconUrl = "https://novelbins.com/favicon.ico",
        webviewNeeded = false,
        runnerConcurrency = 3,
        runnerCooldown = 1000L,
        maxAttempts = 3
    )

    override suspend fun search(query: String, page: Int): List<SearchResultDto> {
        val searchUrl = "${metadata.baseUrl}/search-results/?query=${query.replace(" ", "+")}"
        val doc = http.document(searchUrl) ?: return emptyList()

        return doc.select(".mt-card-item").mapNotNull { element ->
            val title = element.select("h3.mt-card-name").text().trim()
            val url = element.select(".mt-card-avatar a").attr("abs:href")
            val coverStyle = element.select(".mt-card-avatar").attr("style")
            val coverUrl = if (coverStyle.contains("url('")) {
                coverStyle.substringAfter("url('").substringBefore("')")
            } else null

            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null

            SearchResultDto(
                url = url,
                title = title,
                coverUrl = coverUrl,
                author = null
            )
        }
    }

    override suspend fun getNovelDetails(novelUrl: String): NovelDto {
        val doc = http.document(novelUrl) ?: throw IOException("Failed to fetch novel details from $novelUrl")

        val titleElement = doc.select(".novel-short-info h1").first()
        val title = titleElement?.ownText() ?: ""
        val author = doc.select(".novel-short-info p:contains(Author:)").text().replace("Author: ", "").trim()
        val coverUrl = doc.select("img.novel-photo").attr("abs:src")
        val description = doc.select(".novel-short-info p").getOrNull(7)?.text() ?: ""

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
        val permalink = novelUrl.removeSuffix("/").split("/").last()
        var novelId = permalink.split("-").lastOrNull { it.all { c -> c.isDigit() } } ?: ""

        if (novelId.isEmpty()) {
            val bookmarkLink = doc.select("a[href^='javascript:bookmark']").attr("href")
            novelId = bookmarkLink.substringAfter("'").substringBefore("'")
        }

        val tabLinks = doc.select("a.ch[data-toggle='tab']")

        val chapters = if (tabLinks.isEmpty()) {
            doc.select(".chapters .mt-card-item h3.mt-card-name a").map { element ->
                ChapterDto(
                    url = element.attr("abs:href"),
                    title = element.text()
                )
            }
        } else {
            val list = mutableListOf<ChapterDto>()
            for (tabLink in tabLinks) {
                val tabIndex = tabLink.attr("href").replace("#", "")
                list.addAll(fetchChaptersViaAjax(novelId, tabIndex, permalink, novelUrl))
            }
            list
        }

        return chapters.distinctBy { it.url }.mapIndexed { index, chapter ->
            chapter.copy(index = index + 1)
        }
    }

    private suspend fun fetchChaptersViaAjax(
        novelId: String,
        tab: String,
        permalink: String,
        refererUrl: String
    ): List<ChapterDto> {
        val url = "${metadata.baseUrl}/ajax/"
        val requestBody = FormBody.Builder()
            .add("action", "get_chapters")
            .add("id", novelId)
            .add("tab", tab)
            .build()

        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to refererUrl,
            "Origin" to metadata.baseUrl,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )

        val html = try {
            http.post(url, headers, requestBody).use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(metadata.name, "Error during AJAX POST", e)
            null
        } ?: return emptyList()

        val chapters = mutableListOf<ChapterDto>()
        try {
            val jsonArray = JSONArray(html)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val chapterNum = obj.getString("chapter")
                val title = obj.getString("title")
                val num = chapterNum.toIntOrNull() ?: (i + 1)
                chapters.add(
                    ChapterDto(
                        url = "${metadata.baseUrl}/novel/$permalink/chapter/$chapterNum/",
                        title = title,
                        index = num
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(metadata.name, "Error parsing AJAX response", e)
        }
        return chapters
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val doc = http.document(chapterUrl) ?: return null

        val content = http.cleanHtml(doc, ".reader, #chr-content, #chapter-content")

        return Jsoup.parse(content).apply {
            select("a[href*='novelbin'], a[href*='facebook'], a[href*='twitter']").remove()
        }.body().html().trim()
    }

    override fun getListings(): List<ListingDto> {
        return listOf(
            ListingDto("popular", "Popular Novels"),
            ListingDto("latest", "Latest Release")
        )
    }

    override suspend fun getListingNovels(listingId: String, page: Int): List<SearchResultDto> {
        val sort = if (listingId == "latest") "latest-release-novel" else "hot-novel"
        val url = "${metadata.baseUrl}/sort/$sort?page=$page"
        val doc = http.document(url) ?: return emptyList()

        return doc.select(".list-novel .row").mapNotNull { element ->
            val titleElement = element.selectFirst("h3.novel-title a") ?: return@mapNotNull null
            val title = titleElement.text()
            val novelUrl = titleElement.attr("abs:href")
            val coverUrl = element.select("img.cover").attr("abs:src")

            if (title.isEmpty() || novelUrl.isEmpty()) return@mapNotNull null

            SearchResultDto(
                url = novelUrl,
                title = title,
                coverUrl = coverUrl.ifEmpty { null },
                author = null
            )
        }
    }
}
