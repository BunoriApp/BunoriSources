package com.halovoid.bunorisources.crawler

import android.util.Log
import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.ExtensionMetadata
import com.halovoid.bunori.extension.api.models.ListingDto
import com.halovoid.bunori.extension.api.models.NovelDto
import com.halovoid.bunori.extension.api.models.SearchResultDto
import org.json.JSONArray
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Extension source implementation for Royal Road (royalroad.com).
 */
class RoyalRoad(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "royalroad",
        name = "Royal Road",
        version = "1.0.0",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://www.royalroad.com",
        iconUrl = "https://www.royalroad.com/favicon.ico",
        webviewNeeded = false,
        runnerConcurrency = 3,
        runnerCooldown = 1000L,
        maxAttempts = 3
    )

    override suspend fun search(query: String, page: Int): List<SearchResultDto> {
        val searchUrl = "${metadata.baseUrl}/fictions/search?title=${query.replace(" ", "+")}&page=$page"
        val doc = http.document(searchUrl) ?: return emptyList()

        return doc.select(".fiction-list-item").mapNotNull { element ->
            val titleElement = element.selectFirst(".fiction-title a")
            val title = titleElement?.text()?.trim() ?: ""
            val url = titleElement?.attr("abs:href") ?: ""
            val coverUrl = element.selectFirst("img[data-type='cover']")?.attr("abs:src")
            val author = element.selectFirst(".author a")?.text()?.trim()

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

        val title = doc.selectFirst(".fic-header h1")?.text()?.trim() ?: ""
        val author = doc.selectFirst(".fic-header h4 a")?.text()?.trim()

        var coverUrl = doc.selectFirst(".fic-header img.thumbnail")?.attr("abs:src") ?: ""
        if (coverUrl.contains("nocover")) {
            coverUrl = ""
        }

        val descriptionElement = doc.selectFirst(".description .hidden-content")
            ?: doc.selectFirst(".description")
        val description = descriptionElement?.text()?.trim() ?: ""

        val chapters = getChapterList(novelUrl, doc)

        return NovelDto(
            url = novelUrl,
            title = title,
            author = author?.ifEmpty { null },
            coverUrl = coverUrl.ifEmpty { null },
            description = description.ifEmpty { null },
            chapters = chapters
        )
    }

    private fun getChapterList(novelUrl: String, doc: org.jsoup.nodes.Document): List<ChapterDto> {
        val chapters = mutableListOf<ChapterDto>()

        val scriptContent = doc.getElementsByTag("script").firstOrNull { it.html().contains("window.chapters") }?.html()

        if (scriptContent != null) {
            try {
                val regex = Regex("""window\.chapters\s*=\s*(\[.*?\]);""")
                val matchResult = regex.find(scriptContent)
                if (matchResult != null) {
                    val jsonArray = JSONArray(matchResult.groupValues[1])
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val chapTitle = obj.getString("title")
                        val chapUrl = obj.getString("url")
                        val finalUrl = if (chapUrl.startsWith("http")) chapUrl else "${metadata.baseUrl}$chapUrl"

                        chapters.add(
                            ChapterDto(
                                url = finalUrl,
                                title = chapTitle,
                                index = i + 1
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(metadata.name, "Error parsing window.chapters JSON", e)
            }
        }

        // Fallback to table parsing
        if (chapters.isEmpty()) {
            doc.select("#chapters tbody tr.chapter-row").forEachIndexed { index, element ->
                val link = element.selectFirst("a[href]")
                if (link != null) {
                    chapters.add(
                        ChapterDto(
                            url = link.attr("abs:href"),
                            title = link.text().trim(),
                            index = index + 1
                        )
                    )
                }
            }
        }

        return chapters
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val doc = http.document(chapterUrl) ?: return null

        val content = http.cleanHtml(doc, ".chapter-content")

        return Jsoup.parse(content).apply {
            select("span").forEach { span ->
                val text = span.text()
                if (text.contains("Royal Road is the home of this novel", ignoreCase = true) ||
                    text.contains("support the author", ignoreCase = true) ||
                    text.contains("stolen from", ignoreCase = true)
                ) {
                    span.remove()
                }
            }
        }.body().html().trim()
    }

    override fun getListings(): List<ListingDto> {
        return listOf(
            ListingDto("best-rated", "Best Rated"),
            ListingDto("trending", "Trending"),
            ListingDto("popular", "Popular this week")
        )
    }

    override suspend fun getListingNovels(listingId: String, page: Int): List<SearchResultDto> {
        val endpoint = when (listingId) {
            "best-rated" -> "fictions/best-rated"
            "trending" -> "fictions/trending"
            "popular" -> "fictions/weekly-popular"
            else -> "fictions/best-rated"
        }
        val url = "${metadata.baseUrl}/$endpoint?page=$page"
        val doc = http.document(url) ?: return emptyList()

        return doc.select(".fiction-list-item").mapNotNull { element ->
            val titleElement = element.selectFirst(".fiction-title a") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val novelUrl = titleElement.attr("abs:href")
            val coverUrl = element.selectFirst("img[data-type='cover']")?.attr("abs:src")
            val author = element.selectFirst(".author a")?.text()?.trim()

            if (title.isEmpty() || novelUrl.isEmpty()) return@mapNotNull null

            SearchResultDto(
                url = novelUrl,
                title = title,
                coverUrl = coverUrl?.ifEmpty { null },
                author = author?.ifEmpty { null }
            )
        }
    }
}
