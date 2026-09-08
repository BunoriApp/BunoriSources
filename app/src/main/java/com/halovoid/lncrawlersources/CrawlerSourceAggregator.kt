package com.halovoid.lncrawlersources

import com.halovoid.lncrawler.api.core.crawler.Crawler
import com.halovoid.lncrawlersources.crawler.AsiaNovel
import com.halovoid.lncrawlersources.crawler.NovelArchive
import com.halovoid.lncrawlersources.crawler.NovelBins
import com.halovoid.lncrawlersources.crawler.NovelFire
import com.halovoid.lncrawlersources.crawler.NovelFull
import com.halovoid.lncrawlersources.crawler.NovelPhoenix
import com.halovoid.lncrawlersources.crawler.Novgo
import com.halovoid.lncrawlersources.crawler.RoyalRoad

class CrawlerSourceAggregator {
    fun getCrawlers(): List<Crawler> {
        return listOf(
            NovelBins(),
            AsiaNovel(),
            NovelArchive(),
            NovelFull(),
            Novgo(),
            NovelPhoenix(),
            NovelFire(),
            RoyalRoad()
        )
    }

    fun getMinAppVersion(): String {
        return "1.0.10"
    }
}