package com.halovoid.bunorisources.crawler

import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.ExtensionMetadata
import com.halovoid.bunori.extension.api.models.NovelDto
import com.halovoid.bunori.extension.api.models.SearchResultDto
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException

/**
 * Extension source implementation for Novel Fire (novelfire.net).
 */
class NovelFire(
    private val http: ExtensionHttpClient
) : IExtension {

    override val metadata = ExtensionMetadata(
        id = "novelfire",
        name = "Novel Fire",
        version = "1.0.2",
        apiVersion = 1,
        lang = "en",
        baseUrl = "https://novelfire.net",
        iconUrl = "https://novelfire.net/favicon.ico",
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
                            url = "${metadata.baseUrl}/book/$slug",
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

        val chapters = getChapterList(cleanNovelUrl, doc)

        return NovelDto(
            url = cleanNovelUrl,
            title = title,
            author = author.ifEmpty { null },
            coverUrl = coverUrl.ifEmpty { null },
            description = description.ifEmpty { null },
            chapters = chapters
        )
    }

    private suspend fun getChapterList(cleanNovelUrl: String, doc: org.jsoup.nodes.Document): List<ChapterDto> {
        val postId = doc.select("#novel-report").attr("report-post_id").ifEmpty {
            doc.select("[report-post_id]").attr("report-post_id").ifEmpty {
                Regex("""report-post_id=["']?(\d+)["']?""").find(doc.html())?.groupValues?.get(1) ?: ""
            }
        }

        if (postId.isNotEmpty()) {
            val ajaxChapters = fetchChaptersViaAjax(cleanNovelUrl, postId)
            if (ajaxChapters.isNotEmpty()) {
                return ajaxChapters
            }
        }

        return fetchChaptersViaHtml(cleanNovelUrl)
    }

    private suspend fun fetchChaptersViaAjax(cleanNovelUrl: String, postId: String): List<ChapterDto> {
        val ajaxUrl = "${metadata.baseUrl}/ajax/listChapterDataAjax" +
            "?draw=1" +
            "&start=0" +
            "&length=-1" +
            "&post_id=$postId" +
            "&order[0][column]=0" +
            "&order[0][dir]=asc" +
            "&order[0][name]=cmm_posts_detail.n_sort" +
            "&columns[0][data]=n_sort" +
            "&columns[0][name]=cmm_posts_detail.n_sort" +
            "&columns[0][searchable]=true" +
            "&columns[0][orderable]=true" +
            "&columns[0][search][value]=" +
            "&columns[0][search][regex]=false" +
            "&columns[1][data]=bookmark_created_at" +
            "&columns[1][name]=bookmark_chapters.created_at" +
            "&columns[1][searchable]=false" +
            "&columns[1][orderable]=true" +
            "&columns[1][search][value]=" +
            "&columns[1][search][regex]=false" +
            "&search[value]=" +
            "&search[regex]=false" +
            "&only_bookmark=false" +
            "&_=${System.currentTimeMillis()}"

        val headers = mapOf(
            "Referer" to cleanNovelUrl,
            "X-Requested-With" to "XMLHttpRequest"
        )
        val response = http.fetch(ajaxUrl, headers) ?: return emptyList()

        return try {
            val json = JSONObject(response)
            val dataArray = json.optJSONArray("data") ?: return emptyList()
            val list = mutableListOf<ChapterDto>()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val rawTitle = item.optString("title").ifEmpty { item.optString("slug") }
                val title = Jsoup.parse(rawTitle).text().replace("\u200B", "").trim()
                val nSort = item.optInt("n_sort", -1)

                val chapUrl = if (nSort > 0) {
                    "$cleanNovelUrl/chapter-$nSort"
                } else {
                    val slug = item.optString("slug")
                    if (slug.isNotEmpty()) "$cleanNovelUrl/$slug" else continue
                }

                list.add(
                    ChapterDto(
                        url = chapUrl,
                        title = title.ifEmpty { "Chapter ${if (nSort > 0) nSort else i + 1}" },
                        index = if (nSort > 0) nSort else i + 1
                    )
                )
            }
            list.sortedBy { it.index }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchChaptersViaHtml(cleanNovelUrl: String): List<ChapterDto> {
        val chapterListUrl = "$cleanNovelUrl/chapters"
        val chaptersDoc = http.document(chapterListUrl) ?: return emptyList()

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
            val pagesToFetch = minOf(maxPage, 10)
            for (i in 2..pagesToFetch) {
                val pageDoc = http.document("$chapterListUrl?page=$i")
                if (pageDoc != null) chapters.addAll(parseChapters(pageDoc))
            }
        }

        return chapters.distinctBy { it.url }.mapIndexed { index, chapter ->
            chapter.copy(index = index + 1)
        }
    }

    override suspend fun getChapterContent(chapterUrl: String): String? {
        val novelUrl = chapterUrl.substringBeforeLast("/")
        val headers = mapOf("Referer" to novelUrl)
        val doc = http.document(chapterUrl, headers) ?: return null
        val content = http.cleanHtml(
            doc,
            "#content",
            listOf("script", "style", "iframe", ".nf-ads", ".box-notice", ".box-notification")
        )
        return Jsoup.parse(content).body().html().trim()
    }
}
