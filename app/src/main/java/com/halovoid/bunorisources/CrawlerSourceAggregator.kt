package com.halovoid.bunorisources

import com.halovoid.bunori.extension.api.IExtension
import com.halovoid.bunori.extension.api.http.ExtensionHttpClient
import com.halovoid.bunorisources.crawler.AsiaNovel
import com.halovoid.bunorisources.crawler.NovelArchive
import com.halovoid.bunorisources.crawler.NovelBins
import com.halovoid.bunorisources.crawler.NovelFire
import com.halovoid.bunorisources.crawler.NovelFull
import com.halovoid.bunorisources.crawler.NovelPhoenix
import com.halovoid.bunorisources.crawler.Novgo
import com.halovoid.bunorisources.crawler.RoyalRoad

class ExtensionSourceAggregator(private val http: ExtensionHttpClient) {
    fun getExtensions(): List<IExtension> {
        return listOf(
            NovelBins(http),
            AsiaNovel(http),
            NovelArchive(http),
            NovelFull(http),
            Novgo(http),
            NovelPhoenix(http),
            NovelFire(http),
            RoyalRoad(http)
        )
    }
}