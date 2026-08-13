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
        // Step 1: Extract the Video ID straight out of the URL (e.g., 11512831)
        val videoId = Regex("""/video/(\d+)""").find(data)?.groupValues?.get(1)
        
        val document = app.get(data).document
        var iframeUrl = document.selectFirst("meta[itemprop='embedURL']")?.attr("content") 
            ?: document.selectFirst("iframe")?.attr("src")

        var videoUrl: String? = null

        if (!iframeUrl.isNullOrEmpty()) {
            iframeUrl = fixUrl(iframeUrl).replace("&amp;", "&")
            val playerHtml = app.get(iframeUrl, headers = mapOf("Referer" to data)).text
            
            // Step 2: Use your discovery! Hunt the player code for the .m3u8 link
            videoUrl = Regex("""(https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*)""").find(playerHtml)?.groupValues?.get(1)
            
            // Step 3: If it's still hidden, we literally build the URL ourselves!
            if (videoUrl.isNullOrEmpty() && videoId != null) {
                videoUrl = "${mainUrl}/player/m3u8_$videoId.m3u8"
            }
        }

        if (!videoUrl.isNullOrEmpty()) {
            val cleanUrl = videoUrl.replace("&amp;", "&").replace("\\", "")
            
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = cleanUrl,
                    type = INFER_TYPE
                ) {
                    // Spoofing the origin so the server accepts our generated link
                    this.referer = mainUrl
                    this.headers = mapOf(
                        "Referer" to data,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
        } else if (!iframeUrl.isNullOrEmpty()) {
            loadExtractor(iframeUrl, data, subtitleCallback, callback)
        }
        
        return true
    }
}
