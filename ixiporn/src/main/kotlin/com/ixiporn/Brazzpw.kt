package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Brazzpw : MainAPI() {
    override var mainUrl              = "https://brazzpw.xyz"
    override var name                 = "Brazzpw"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/page/" to "Latest Updates"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.replace("page/", "") else request.data + "$page/"
        val document = app.get(url).document
        
        val home = document.select("article.loop-video, article.thumb-block").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        
        val title = linkElement.attr("title").trim()
        if (title.isEmpty()) return null
        
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..10) {
            val url = if (i == 1) "${mainUrl}/?s=$safeQuery" else "${mainUrl}/page/$i/?s=$safeQuery"
            val document = app.get(url).document
            val results = document.select("article.loop-video, article.thumb-block").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) searchResponse.addAll(results) else break
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Added the exact meta tags you found in the HTML snippet!
        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?.replace("BrazzPW.XYZ -", "")?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // I am waiting for the video player HTML snippet to finish this!
        return true
    }
}
