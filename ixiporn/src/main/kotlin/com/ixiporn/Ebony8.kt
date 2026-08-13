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

    // ADDED: A custom shelf for our 20+ Minute filter (pulling from latest updates)
    override val mainPage = mainPageOf(
        "${mainUrl}/categories/" to "Categories",
        "${mainUrl}/latest-updates/" to "Latest Updates",
        "${mainUrl}/latest-updates/" to "20+ Minutes",
        "${mainUrl}/most-popular/" to "Most Popular",
        "${mainUrl}/top-rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "$page/").document
        
        // We check if the user is looking at our custom long video shelf
        val isLongShelf = request.name == "20+ Minutes"
        
        // We pass that rule into our search result builder
        val home = document.select("div.item").mapNotNull { it.toSearchResult(isLongShelf) }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    // ADDED: The requireLong rule
    private fun Element.toSearchResult(requireLong: Boolean = false): SearchResponse? {
        // --- DURATION FILTER ---
        if (requireLong) {
            val durationText = this.selectFirst("div.duration")?.text()?.trim() ?: "0:00"
            val parts = durationText.split(":")
            
            // Convert mm:ss or hh:mm:ss into total minutes
            val minutes = when (parts.size) {
                2 -> parts[0].toIntOrNull() ?: 0 // format like 07:22
                3 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0) // format like 1:07:22
                else -> 0
            }
            
            // If it's under 20 minutes, return null (skip this video entirely!)
            if (minutes < 20) return null 
        }

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

        var videoUrl = document.selectFirst("video.fp-engine, video")?.attr("src")
        
        if (videoUrl.isNullOrEmpty()) {
            videoUrl = Regex("""(https?://[^"'\s]+?\.mp4[^"'\s]*)""").find(html)?.groupValues?.get(1)
        }

        if (videoUrl.isNullOrEmpty()) {
            val iframeUrl = document.selectFirst("iframe")?.attr("src")
            if (!iframeUrl.isNullOrEmpty()) {
                loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
                return true
            }
        }

        if (!videoUrl.isNullOrEmpty()) {
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
