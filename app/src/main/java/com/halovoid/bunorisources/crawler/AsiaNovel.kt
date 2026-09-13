package com.halovoid.bunorisources.crawler

import android.util.Log
import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.ExtensionMetadata
import com.halovoid.bunori.extension.api.models.NovelDto
import com.halovoid.bunori.extension.api.models.SearchResultDto
import org.json.JSONObject
import java.io.IOException

/**
 * Extension source implementation for AsiaNovel (asianovel.net).
 */
class AsiaNovel(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "asianovel",
        name = "AsiaNovel",
        version = "1.0.1",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://www.asianovel.net",
        iconUrl = "https://www.asianovel.net/favicon.ico",
        webviewNeeded = false,
        runnerConcurrency = 3,
        runnerCooldown = 1000L,
        maxAttempts = 3
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/04.1"
    )

    override suspend fun search(query: String, page: Int): List<SearchResultDto> {
        val searchUrl = "${metadata.baseUrl}/?s=${query.replace(" ", "+")}&post_type=any&sentence=0&orderby=modified&order=desc"
        val doc = http.document(searchUrl, headers) ?: return emptyList()

        val results = mutableListOf<SearchResultDto>()
        doc.select("ul#search-result-list li.card").forEach { card ->
            val storyLink = card.select("a[href*='/story/']").first()
            if (storyLink != null) {
                val url = storyLink.attr("abs:href")
                val title = storyLink.text().ifEmpty {
                    card.select(".card__title").text()
                }.trim()
                val coverUrl = card.select("img.wp-post-image").attr("abs:src")

                if (title.isNotEmpty() && url.isNotEmpty() && results.none { it.url == url }) {
                    results.add(
                        SearchResultDto(
                            url = url,
                            title = title,
                            coverUrl = coverUrl.ifEmpty { null }
                        )
                    )
                }
            }
        }
        return results
    }

    override suspend fun getNovelDetails(novelUrl: String): NovelDto {
        val doc = http.document(novelUrl, headers) ?: throw IOException("Failed to fetch novel metadata from $novelUrl")

        val title = doc.select("meta[property='og:title']").attr("content")
            .substringBefore(" - Asianovel").trim()
        val author = doc.select("meta[property='article:author']").attr("content")
            .let { if (it.isEmpty()) doc.select("header.story__headline em.story__author a").text() else it }
            .trim()
        val coverUrl = doc.select("meta[property='og:image']").attr("content")
        val description = doc.select("meta[property='og:description']").attr("content")

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

    private fun getChapterList(novelUrl: String, doc: org.jsoup.nodes.Document): List<ChapterDto> {
        val chapters = mutableListOf<ChapterDto>()

        val scripts = doc.select("script[type='application/ld+json']")
        for (script in scripts) {
            try {
                val json = JSONObject(script.data())
                val graph = json.optJSONArray("@graph")
                if (graph != null) {
                    for (i in 0 until graph.length()) {
                        val item = graph.getJSONObject(i)
                        if (item.optString("@type") == "ItemList" && item.optString("name") == "Chapters") {
                            val list = item.optJSONArray("itemListElement")
                            if (list != null) {
                                for (j in 0 until list.length()) {
                                    val chapterObj = list.getJSONObject(j)
                                    val url = chapterObj.optString("url")
                                    val position = chapterObj.optInt("position", j + 1)
                                    val rawTitle = chapterObj.optString("name").ifBlank {
                                        chapterObj.optString("title", "Chapter $position")
                                    }
                                    val title = rawTitle.ifBlank { "Chapter $position" }
                                    if (url.isNotEmpty()) {
                                        chapters.add(
                                            ChapterDto(
                                                url = url,
                                                title = title,
                                                index = position
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(metadata.name, "Error parsing JSON-LD", e)
            }
        }

        if (chapters.isEmpty()) {
            doc.select("ol.chapter-group__list li.chapter-group__list-item a.chapter-group__list-item-link").forEachIndexed { index, element ->
                chapters.add(
                    ChapterDto(
                        url = element.attr("abs:href"),
                        title = element.text().trim(),
                        index = index + 1
                    )
                )
            }
        }

        return chapters.sortedBy { it.index }
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val doc = http.document(chapterUrl, headers) ?: return null

        val contentElement = doc.select("#chapter-content").first() ?: return null
        contentElement.select(".asian-ads-top-content, .asian-ads-bottom-content, script, ins").remove()

        return contentElement.html().trim()
    }
}
