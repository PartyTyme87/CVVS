package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Fullhd : MainAPI() {
    override var mainUrl              = "https://www.fullhd.to"
    override var name                 = "Fullhd"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/latest-updates/" to "Latest Updates",
        "${mainUrl}/top-rated/" to "Top Rated",
        "${mainUrl}/most-popular/" to "Most Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            request.data + "$page/"
        }
        
        val document = app.get(url).document
        
        val home = document.select("div.item, div.video-item").mapNotNull { it.toSearchResult() }

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
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
            
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
            val url = "${mainUrl}/search/$safeQuery/$i/"
            val document = app.get(url).document
            val results = document.select("div.item, div.video-item").mapNotNull { it.toSearchResult() }

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
        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        
        // Loop through all <source> tags inside the video player
        val sources = document.select("source")
        var foundLinks = false
        
        for (source in sources) {
            val src = source.attr("src")
            val label = source.attr("label") ?: ""
            
            if (src.isNotBlank()) {
                foundLinks = true
                
                // Determine quality based on the label you found (e.g., "720p", "2160p")
                val quality = when {
                    label.contains("2160") -> Qualities.P2160.value
                    label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value
                    label.contains("480") -> Qualities.P480.value
                    label.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} $label".trim(),
                        url = src,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to data, "Origin" to mainUrl)
                        this.quality = quality
                    }
                )
            }
        }
        
        // Fallback to the main video tag src if no source tags were found
        if (!foundLinks) {
            val videoUrl = document.selectFirst("video")?.attr("src")
            if (!videoUrl.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to data, "Origin" to mainUrl)
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
