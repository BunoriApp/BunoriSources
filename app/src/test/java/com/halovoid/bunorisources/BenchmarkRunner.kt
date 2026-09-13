package com.halovoid.bunorisources

import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunori.extension.api.models.ChapterDto
import com.halovoid.bunori.extension.api.models.NovelDto
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Test
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * End-to-end performance benchmarking tool for Bunori crawlers.
 *
 * Runs the actual Kotlin crawler classes against real novel URLs, measures exact
 * phase-by-phase latency (metadata, chapter list, chapter content), and outputs a
 * comprehensive benchmark report.
 *
 * Usage:
 *   ./gradlew testDebugUnitTest --tests "*BenchmarkRunner*"
 *   ./gradlew testDebugUnitTest --tests "*BenchmarkRunner*" -Durls="url1,url2,url3"
 */
class BenchmarkRunner {

    data class BenchmarkResult(
        val url: String,
        val sourceName: String,
        val sourceId: String,
        val novelTitle: String,
        val author: String,
        val chapterCount: Int,
        val timeDetailsMs: Long,
        val timeContentMs: Long,
        val totalTimeMs: Long,
        val firstChapterTitle: String,
        val contentLength: Int,
        val success: Boolean,
        val errorMessage: String? = null
    ) {
        val speedBadge: String
            get() = when {
                !success -> "❌ FAILED"
                totalTimeMs < 1500 -> "⚡ ULTRA FAST (< 1.5s)"
                totalTimeMs < 3000 -> "🟢 FAST (1.5 - 3.0s)"
                totalTimeMs < 5000 -> "🟡 MODERATE (3.0 - 5.0s)"
                else -> "🔴 SLOW (> 5.0s)"
            }
    }

    private val testHttpClient = object : ExtensionHttpClient {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    return try {
                        okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
                    } catch (_: Exception) {
                        okhttp3.Dns.SYSTEM.lookup(hostname)
                    }
                }
            })
            .followRedirects(true)
            .build()

        private val userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
                val resp = get(url, headers)
                if (!resp.isSuccessful) {
                    println("  ⚠️ [fetch HTTP ${resp.code}] $url")
                }
                resp.use { it.body?.string() }
            } catch (e: Exception) {
                println("  ❌ [fetch error] ${e.javaClass.simpleName}: ${e.message} for $url")
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

    private val registeredExtensions: List<IExtension> by lazy {
        ExtensionSourceAggregator(testHttpClient).getExtensions()
    }

    private fun findExtensionForUrl(url: String): IExtension? {
        val targetHost = try {
            URI(url).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }

        return registeredExtensions.firstOrNull { ext ->
            val baseHost = try {
                URI(ext.metadata.baseUrl).host?.lowercase()?.removePrefix("www.") ?: ""
            } catch (_: Exception) {
                ""
            }
            targetHost.isNotEmpty() && (targetHost == baseHost || targetHost.endsWith(".$baseHost") || baseHost.endsWith(".$targetHost"))
        }
    }

    @Test
    fun runBenchmark() = runBlocking {
        val defaultUrls = listOf(
            "https://novelfire.net/book/shadow-slave",
            "https://novelfull.com/shadow-slave.html",
            "https://www.royalroad.com/fiction/21220/mother-of-learning"
        )

        val rawInput = System.getProperty("urls")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("BENCHMARK_URLS")?.takeIf { it.isNotBlank() }

        val urlsToTest = if (rawInput != null) {
            rawInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            defaultUrls
        }

        println("\n" + "=".repeat(78))
        println("               BUNORI CRAWLER PERFORMANCE BENCHMARK")
        println("=".repeat(78))
        println("Testing ${urlsToTest.size} Novel URL(s) with actual Kotlin crawler implementations:")
        urlsToTest.forEachIndexed { i, u -> println("  [${i + 1}] $u") }
        println("-".repeat(78) + "\n")

        val results = mutableListOf<BenchmarkResult>()

        for ((index, url) in urlsToTest.withIndex()) {
            println("[${index + 1}/${urlsToTest.size}] Benchmarking: $url")

            val extension = findExtensionForUrl(url)
            if (extension == null) {
                println("  ❌ Error: No registered crawler found matching URL host.")
                results.add(
                    BenchmarkResult(
                        url = url,
                        sourceName = "Unknown",
                        sourceId = "unknown",
                        novelTitle = "N/A",
                        author = "N/A",
                        chapterCount = 0,
                        timeDetailsMs = 0,
                        timeContentMs = 0,
                        totalTimeMs = 0,
                        firstChapterTitle = "N/A",
                        contentLength = 0,
                        success = false,
                        errorMessage = "No matching extension registered for domain"
                    )
                )
                println()
                continue
            }

            println("  -> Matched Source: ${extension.metadata.name} (id: ${extension.metadata.id})")

            var details: NovelDto? = null
            var firstChapter: ChapterDto? = null
            var content: String? = null
            var timeDetailsMs = 0L
            var timeContentMs = 0L
            var errorMessage: String? = null

            // Phase 1: Novel Details & Chapters
            try {
                print("  -> Fetching novel metadata & chapters... ")
                val t0 = System.currentTimeMillis()
                details = extension.getNovelDetails(url)
                timeDetailsMs = System.currentTimeMillis() - t0
                println("DONE in ${timeDetailsMs}ms (${details.chapters.size} chapters found)")
            } catch (e: Exception) {
                errorMessage = "getNovelDetails failed: ${e.message}"
                println("FAILED (${e.message})")
            }

            // Phase 2: Chapter Content
            if (details != null && details.chapters.isNotEmpty()) {
                firstChapter = details.chapters.firstOrNull()
                if (firstChapter != null) {
                    try {
                        print("  -> Fetching sample chapter content ('${firstChapter.title}')... ")
                        val t1 = System.currentTimeMillis()
                        content = extension.getChapterContent(firstChapter.url)
                        timeContentMs = System.currentTimeMillis() - t1
                        println("DONE in ${timeContentMs}ms (${content?.length ?: 0} chars)")
                    } catch (e: Exception) {
                        println("FAILED (${e.message})")
                    }
                }
            }

            val totalTimeMs = timeDetailsMs + timeContentMs
            val success = details != null && details.chapters.isNotEmpty()

            val result = BenchmarkResult(
                url = url,
                sourceName = extension.metadata.name,
                sourceId = extension.metadata.id,
                novelTitle = details?.title ?: "N/A",
                author = details?.author ?: "Unknown",
                chapterCount = details?.chapters?.size ?: 0,
                timeDetailsMs = timeDetailsMs,
                timeContentMs = timeContentMs,
                totalTimeMs = totalTimeMs,
                firstChapterTitle = firstChapter?.title ?: "N/A",
                contentLength = content?.length ?: 0,
                success = success,
                errorMessage = errorMessage
            )
            results.add(result)

            val chRate = if (timeDetailsMs > 0 && result.chapterCount > 0) {
                (result.chapterCount * 1000.0 / timeDetailsMs).toInt()
            } else 0

            println("  📊 Result: ${result.speedBadge}")
            println("     Total Time: ${totalTimeMs}ms (Details: ${timeDetailsMs}ms | Content: ${timeContentMs}ms)")
            if (success) {
                println("     Novel: \"${result.novelTitle}\" by ${result.author}")
                println("     Throughput: $chRate chapters/sec")
            }
            println()
        }

        // Consolidated Final Report
        println("\n" + "=".repeat(78))
        println("                        BENCHMARK SUMMARY REPORT")
        println("=".repeat(78))
        println(
            String.format(
                "%-14s | %-10s | %-9s | %-9s | %-8s | %-18s",
                "Source", "Details", "Content", "Total", "Chapters", "Rating"
            )
        )
        println("-".repeat(78))
        for (r in results) {
            println(
                String.format(
                    "%-14s | %8sms | %7sms | %7sms | %8d | %-18s",
                    r.sourceName.take(14),
                    r.timeDetailsMs,
                    r.timeContentMs,
                    r.totalTimeMs,
                    r.chapterCount,
                    r.speedBadge
                )
            )
        }
        println("=".repeat(78) + "\n")
    }
}
