package com.halovoid.bunorisources

import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunorisources.crawler.NovelFire
import com.halovoid.bunorisources.crawler.NovelFull
import com.halovoid.bunorisources.crawler.NovelPhoenix
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * CloudStream-style standalone test runner.
 * Allows contributors to test any crawler locally with 1 command:
 *
 *   ./gradlew test --tests "*SourceTester*"
 */
class SourceTester {

    /** Real HTTP client implementing ExtensionHttpClient for local tests */
    private val testHttpClient = object : ExtensionHttpClient {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        override suspend fun get(url: String, headers: Map<String, String>): Response {
            val req = Request.Builder().url(url).header("User-Agent", userAgent)
            headers.forEach { (k, v) -> req.header(k, v) }
            return client.newCall(req.build()).execute()
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody?): Response {
            val req = Request.Builder().url(url).header("User-Agent", userAgent)
            headers.forEach { (k, v) -> req.header(k, v) }
            if (body != null) req.post(body)
            return client.newCall(req.build()).execute()
        }

        override suspend fun fetch(url: String, headers: Map<String, String>): String? {
            return try {
                get(url, headers).use { it.body?.string() }
            } catch (e: Exception) {
                null
            }
        }

        override suspend fun document(url: String, headers: Map<String, String>): Document? {
            val html = fetch(url, headers) ?: return null
            return Jsoup.parse(html, url)
        }

        override suspend fun download(url: String): ByteArray? {
            return try {
                get(url).use { it.body?.bytes() }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Executes end-to-end verification (search -> novel details -> chapter content) on a source.
     */
    fun testAll(source: IExtension, query: String = "Shadow Slave") = runBlocking {
        println("=== Testing Source: ${source.metadata.name} (${source.metadata.baseUrl}) ===")

        // 1. Search
        println("-> Testing search('$query')...")
        val searchResults = source.search(query, 1)
        println("   Found ${searchResults.size} search results")
        if (searchResults.isNotEmpty()) {
            searchResults.take(3).forEach {
                println("   • [${it.title}] (${it.url})")
            }
        }

        // 2. Novel Details (if search returned results)
        val firstNovel = searchResults.firstOrNull()
        if (firstNovel != null) {
            println("-> Testing getNovelDetails('${firstNovel.url}')...")
            val details = source.getNovelDetails(firstNovel.url)
            println("   Title: ${details.title}")
            println("   Author: ${details.author ?: "Unknown"}")
            println("   Chapters count: ${details.chapters.size}")
            assertTrue("Novel details should contain chapters", details.chapters.isNotEmpty())

            // 3. Chapter Content
            val firstChapter = details.chapters.firstOrNull()
            if (firstChapter != null) {
                println("-> Testing getChapterContent('${firstChapter.url}')...")
                val content = source.getChapterContent(firstChapter.url)
                assertNotNull("Chapter content should not be null", content)
                println("   Content length: ${content?.length ?: 0} chars")
                println("   Sample: ${content?.take(120)?.replace("\n", " ")}...")
            }
        }

        println("✓ Completed tests for ${source.metadata.name}\n")
    }

    @Test
    fun testNovelFull() {
        val source = NovelFull(testHttpClient)
        println("Instantiated: ${source.metadata.name} (id: ${source.metadata.id})")
        assertTrue(source.metadata.baseUrl.isNotEmpty())
    }

    @Test
    fun testNovelFire() {
        val source = NovelFire(testHttpClient)
        println("Instantiated: ${source.metadata.name} (id: ${source.metadata.id})")
        assertTrue(source.metadata.baseUrl.isNotEmpty())
    }

    @Test
    fun testNovelPhoenix() {
        val source = NovelPhoenix(testHttpClient)
        println("Instantiated: ${source.metadata.name} (id: ${source.metadata.id})")
        assertTrue(source.metadata.baseUrl.isNotEmpty())
    }
}
