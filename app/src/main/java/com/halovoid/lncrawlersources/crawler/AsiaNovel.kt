package com.halovoid.lncrawlersources.crawler

import android.util.Log
import com.halovoid.lncrawler.api.core.config.CrawlerConfig
import com.halovoid.lncrawler.api.core.crawler.Crawler
import com.halovoid.lncrawler.domain.models.Chapter
import com.halovoid.lncrawler.domain.models.Novel
import org.json.JSONObject
import java.io.IOException

/**
 * Crawler implementation for AsiaNovel (asianovel.net).
 * Uses Fictioneer theme selectors and handles Cloudflare via Webview.
 */
class AsiaNovel : Crawler() {
    override val name: String = "AsiaNovel"
    override val baseUrl: String = "https://www.asianovel.net"
    override val webviewNeeded: Boolean = true

    override val config: CrawlerConfig
        get() = _config ?: CrawlerConfig(
            userFolderLocation = "",
            maxAttempts = 3,
            runnerConcurrency = 2
        )

    override val chapterPerVolume: Int = 100

    private val mobileUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/04.1"

    override fun canHandle(url: String): Boolean {
        return url.contains("asianovel.net")
    }

    override suspend fun getNovelMetadata(novelUrl: String): Novel {
        val doc = scrapper.document(novelUrl, mapOf("User-Agent" to mobileUserAgent), true) 
            ?: throw IOException("Failed to fetch novel metadata from $novelUrl")
        Log.i(name, "Scraping novel metadata: $novelUrl")

        val title = doc.select("meta[property='og:title']").attr("content")
            .substringBefore(" - Asianovel").trim()
        val author = doc.select("meta[property='article:author']").attr("content")
            .let { if (it.isEmpty()) doc.select("header.story__headline em.story__author a").text() else it }
            .trim()
        val coverUrl = doc.select("meta[property='og:image']").attr("content")
        val description = doc.select("meta[property='og:description']").attr("content")

        return Novel(
            url = novelUrl,
            title = title,
            author = author,
            coverUrl = coverUrl,
            description = description,
            chapters = emptyList(),
            crawlerName = name,
            alternativeNames = null
        )
    }

    override suspend fun getChapterList(novelUrl: String): List<Chapter> {
        val doc = scrapper.document(novelUrl, mapOf("User-Agent" to mobileUserAgent), true) 
            ?: throw IOException("Failed to fetch chapter list from $novelUrl")
        Log.i(name, "Scraping chapter list: $novelUrl")

        val chapters = mutableListOf<Chapter>()

        // Try extracting from JSON-LD first as it's cleaner
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
                                    val position = chapterObj.optInt("position")
                                    if (url.isNotEmpty()) {
                                        chapters.add(
                                            Chapter(
                                                id = 0,
                                                url = url,
                                                novelUrl = novelUrl,
                                                title = "Chapter $position",
                                                index = position,
                                                volumeId = "${novelUrl}_vol_${(position - 1) / chapterPerVolume + 1}",
                                                fileLocation = null
                                            ).apply { scanlationSource = name }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Error parsing JSON-LD", e)
            }
        }

        // Fallback to HTML parsing if JSON-LD didn't work or returned empty
        if (chapters.isEmpty()) {
            doc.select("ol.chapter-group__list li.chapter-group__list-item a.chapter-group__list-item-link").forEachIndexed { index, element ->
                val url = element.attr("abs:href")
                val title = element.text()
                chapters.add(
                    Chapter(
                        id = 0,
                        url = url,
                        novelUrl = novelUrl,
                        title = title,
                        index = index + 1,
                        volumeId = "${novelUrl}_vol_${index / chapterPerVolume + 1}",
                        fileLocation = null
                    ).apply { scanlationSource = name }
                )
            }
        }

        return chapters.sortedBy { it.index }
    }

    override suspend fun getNovelDetails(novelUrl: String): Novel {
        val metadata = getNovelMetadata(novelUrl)
        val chapters = getChapterList(novelUrl)
        return prepareNovel(metadata.copy(chapters = chapters))
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val doc = scrapper.document(chapterUrl, mapOf("User-Agent" to mobileUserAgent), true) ?: return null
        Log.i(name, "Scraping chapter: $chapterUrl")

        val contentElement = doc.select("#chapter-content").first() ?: return null
        
        // Clean ads and other elements
        contentElement.select(".asian-ads-top-content, .asian-ads-bottom-content, script, ins").remove()

        return contentElement.html().trim()
    }

    override suspend fun getSearchResults(query: String): List<Novel> {
        val searchUrl = "$baseUrl/?s=${query.replace(" ", "+")}&post_type=any&sentence=0&orderby=modified&order=desc"
        val doc = scrapper.document(searchUrl, mapOf("User-Agent" to mobileUserAgent), true) ?: return emptyList()

        val results = mutableListOf<Novel>()
        doc.select("ul#search-result-list li.card").forEach { card ->
            // Try to find a story link. It might be in the title or in the card links list.
            val storyLink = card.select("a[href*='/story/']").first()
            if (storyLink != null) {
                val url = storyLink.attr("abs:href")
                // Use the story link text as title, fallback to card title
                val title = storyLink.text().ifEmpty { 
                    card.select(".card__title").text() 
                }
                val coverUrl = card.select("img.wp-post-image").attr("abs:src")
                
                // Avoid duplicates in search results
                if (results.none { it.url == url }) {
                    results.add(
                        Novel(
                            url = url,
                            title = title,
                            author = "",
                            coverUrl = coverUrl,
                            description = "",
                            chapters = emptyList(),
                            crawlerName = name,
                            alternativeNames = null
                        )
                    )
                }
            }
        }
        return results
    }
}
