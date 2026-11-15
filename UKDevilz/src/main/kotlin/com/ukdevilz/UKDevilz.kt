package com.ukdevilz

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class UKDevilz : MainAPI() {
    override var mainUrl              = "https://ukdevilz.com"
    override var name                 = "UKDevilz"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    // Pulls the user's toggle choices natively from Cloudstream
    private val showMariza get() = app.preferences.getBoolean("ukd_mariza_rabbit", true)
    private val showViolet get() = app.preferences.getBoolean("ukd_violet_myers", true)
    private val showVictoria get() = app.preferences.getBoolean("ukd_victoria_june", true)
    private val showYashmine get() = app.preferences.getBoolean("ukd_yashmine_khan", true)

    // Dynamically builds the homepage tables based on active preferences
    override val mainPage: List<MainPageData>
        get() = mainPageOf(
            *listOfNotNull(
                "${mainUrl}/home?range=month" to "Popular",
                "${mainUrl}/now" to "Watching Now",
                "${mainUrl}/home?range=explore" to "Explore",
                "${mainUrl}/home?range=recent" to "Recent",
                if (showMariza) "${mainUrl}/video/mariza%20rabbit" to "Mariza Rabbit" else null,
                if (showViolet) "${mainUrl}/video/Violet%20Myers" to "Violet Myers" else null,
                if (showVictoria) "${mainUrl}/video/victoria%20june" to "Victoria June" else null,
                if (showYashmine) "${mainUrl}/video/yashmine%20khan" to "Yashmine Khan" else null
            ).toTypedArray()
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                "${request.data}&p=$page"
            } else {
                "${request.data}?p=$page"
            }
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        val home = document.select("div.item").mapNotNull { it.toSearchResult() }

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
        val linkElement = this.selectFirst("a.item_link") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = this.attr("data-video-title-base").takeIf { it.isNotBlank() } 
            ?: this.selectFirst("div.title")?.text()?.trim() 
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:image") }
        )

        val isFolder = href.contains("/category/") || href.contains("/tag/") || href.contains("/pornstar/") || href.contains("/studio/")
        
        return if (isFolder) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..3) {
            val url = "${mainUrl}/search/?story=$safeQuery&p=$i"
            
            try {
                val document = app.get(url, headers = defaultHeaders, timeout = 30).document
                val results = document.select("div.item").mapNotNull { it.toSearchResult() }

                if (results.isEmpty()) break
                searchResponse.addAll(results.filter { res -> searchResponse.none { it.url == res.url } })
            } catch (e: Exception) {
                break
            }
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document

        val title = document.selectFirst("div.h_info h1 span")?.text()?.trim() 
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(
            document.selectFirst("div.jw-preview")?.attr("style")?.let { style ->
                Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
            } ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        
        if (url.contains("/category/") || url.contains("/tag/") || url.contains("/pornstar/") || url.contains("/studio/")) {
            val episodes = mutableListOf<Episode>()
            
            for (page in 1..50) {
                val pageUrl = if (page == 1) url else {
                    if (url.contains("?")) "$url&p=$page" else "$url?p=$page"
                }

                try {
                    val pageDoc = if (page == 1) document else app.get(pageUrl, headers = defaultHeaders, timeout = 30).document
                    val pageEpisodes = pageDoc.select("div.item").mapNotNull { elem ->
                        val link = elem.selectFirst("a.item_link") ?: return@mapNotNull null
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        val img = elem.selectFirst("img")
                        
                        val epTitle = elem.attr("data-video-title-base").takeIf { it.isNotBlank() }
                            ?: elem.selectFirst("div.title")?.text()?.trim()
                            ?: "Video"
                            
                        val epPoster = fixUrlNull(
                            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:image") }
                        )
                        
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.posterUrl = epPoster
                        }
                    }

                    if (pageEpisodes.isEmpty()) break
                    
                    val existingUrls = episodes.map { it.data }
                    val newEpisodes = pageEpisodes.filter { it.data !in existingUrls }
                    if (newEpisodes.isEmpty()) break

                    episodes.addAll(newEpisodes)
                } catch (e: Exception) {
                    break
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        }
        
        val tags = document.select("div.video-tags-bar a.vtag span").map { it.text().trim() }
        val recommendations = document.select("div.list_videos div.item").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = defaultHeaders, timeout = 30).text
        var foundLinks = false
        
        val playlistRegex = Regex("""window\.playlist\s*=\s*(\{.*?\});""")
        val match = playlistRegex.find(html)
        
        if (match != null) {
            try {
                val jsonString = match.groupValues[1]
                val sourcesRegex = Regex(""""file"\s*:\s*"([^"]+)".*?"label"\s*:\s*"([^"]+)"""")
                
                sourcesRegex.findAll(jsonString).forEach { srcMatch ->
                    val videoUrl = srcMatch.groupValues[1].replace("\\/", "/")
                    val qualityLabel = srcMatch.groupValues[2]
                    
                    if (videoUrl.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name $qualityLabel",
                                url = fixUrl(videoUrl),
                                type = INFER_TYPE
                            ) {
                                this.referer = data
                                this.quality = qualityLabel.replace(Regex("[^0-9]"), "").toIntOrNull() ?: Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                // Failsafe
            }
        }
        
        if (!foundLinks) {
            val document = org.jsoup.Jsoup.parse(html)
            document.select("video source").mapNotNull { it.attr("src") }.forEach { src ->
                if (src.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name Direct",
                            url = fixUrl(src),
                            type = INFER_TYPE
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            }
        }
        
        return foundLinks
    }
}
