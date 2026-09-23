package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Latestpornvideo : MainAPI() {
    override var mainUrl              = "https://latestpornvideo.com"
    override var name                 = "Latest Porn Video"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/?filter=latest" to "Latest Videos",
        "${mainUrl}/?filter=most-viewed" to "Most Viewed",
        "${mainUrl}/?filter=longest" to "Longest Videos",
        "${mainUrl}/category/porn-movie/" to "Porn Movie",
        "${mainUrl}/category/jav/" to "JAV",
        "${mainUrl}/category/webcam/" to "Webcam"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Handles pagination for both query filters and category folders
            if (request.data.contains("?filter=")) {
                request.data.replace("?", "page/$page/?")
            } else {
                if (request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
            }
        }
        
        val document = app.get(url, referer = "$mainUrl/").document
        val home = document.select("article.loop-video").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        // Checks multiple attributes since titles can shift depending on the specific page layout
        val title = this.attr("data-title").takeIf { it.isNotBlank() }
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: linkElement.attr("data-title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
            
        val posterUrl = fixUrlNull(
            this.attr("data-main-thumb").takeIf { it.isNotBlank() } 
            ?: this.selectFirst("img")?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..3) {
            val url = if (i == 1) {
                "${mainUrl}/?s=$safeQuery"
            } else {
                "${mainUrl}/page/$i/?s=$safeQuery"
            }
            
            try {
                val document = app.get(url, referer = "$mainUrl/").document
                val results = document.select("article.loop-video").mapNotNull { it.toSearchResult() }

                if (results.isEmpty()) break
                searchResponse.addAll(results.filter { res -> searchResponse.none { it.url == res.url } })
            } catch (e: Exception) {
                break
            }
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("meta[itemprop='name']")?.attr("content")?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[itemprop='thumbnailUrl']")?.attr("content"))
        val description = document.selectFirst("meta[itemprop='description']")?.attr("content")?.trim()

        val tags = document.select("div.tags-list a").map { it.text().trim() }
        val recommendations = document.select("div.under-video-block article.loop-video").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var foundLinks = false
        
        // Isolates the external iframe (e.g., lulust.com)
        val iframeSrc = document.selectFirst("div.responsive-player iframe")?.attr("src")
        
        if (!iframeSrc.isNullOrBlank()) {
            val fixedIframe = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
            
            // Primary Method: Let Cloudstream's universal host extractors handle the heavy lifting
            loadExtractor(fixedIframe, data, subtitleCallback, callback)
            foundLinks = true
            
            // Fallback Method: JsUnpacker and Regex grab in case the iframe embeds raw links
            try {
                val iframeHtml = app.get(fixedIframe, referer = data).text
                val unpackedHtml = JsUnpacker(iframeHtml).unpack() ?: iframeHtml
                val cleanHtml = unpackedHtml.replace("\\/", "/")
                
                val mediaRegex = Regex("""(https?://[^"'\s,;]+\.(?:m3u8|mp4)[^"'\s,;]*)""")
                mediaRegex.findAll(cleanHtml).forEach { match ->
                    // FIXED: Restored the proven lambda builder format!
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name HD",
                            url = match.groupValues[1],
                            type = INFER_TYPE
                        ) {
                            this.referer = fixedIframe
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                // Ignore fallback errors
            }
        }
        
        return foundLinks
    }
}
