package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Ebony8 : MainAPI() {
    override var mainUrl              = "https://www.ebony8.com"
    override var name                 = "Ebony8"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/latest-updates/" to "Latest Updates",
        "${mainUrl}/most-popular/" to "Most Popular",
        "${mainUrl}/top-rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "$page/").document
        val home = document.select("div.item").mapNotNull { it.toSearchResult() }

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
        
        val title = this.selectFirst("strong.title")?.text()?.trim() 
            ?: linkElement.attr("title").trim()
            
        if (title.isEmpty()) return null
        
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() } 
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
            val document = app.get("${mainUrl}/search/$safeQuery/$i/").document
            val results = document.select("div.item").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) searchResponse.addAll(results) else break
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val html = document.html() 

        // Attempt 1: Target the specific player class you found
        var videoUrl = document.selectFirst("video.fp-engine, video")?.attr("src")
        
        // Attempt 2: Regex search designed to grab the .mp4 AND the long security token
        if (videoUrl.isNullOrEmpty()) {
            videoUrl = Regex("""(https?://[^"'\s]+?\.mp4[^"'\s]*)""").find(html)?.groupValues?.get(1)
        }

        // Attempt 3: Iframe backup for older videos
        if (videoUrl.isNullOrEmpty()) {
            val iframeUrl = document.selectFirst("iframe")?.attr("src")
            if (!iframeUrl.isNullOrEmpty()) {
                loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
                return true
            }
        }

        if (!videoUrl.isNullOrEmpty()) {
            // Clean up any escaped ampersands in the security token
            val cleanUrl = fixUrl(videoUrl).replace("&amp;", "&")
            
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = cleanUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.headers = mapOf("Referer" to data, "Origin" to mainUrl)
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }
}
