package com.freshporno

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Freshporno : MainAPI() {
    override var mainUrl              = "https://freshporno.org"
    override var name                 = "Freshporno"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/latest-updates/" to "Latest",
        "$mainUrl/most-popular/" to "Most Popular",
        "$mainUrl/top-rated/" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Append standard tube site page numbers
            if (request.data.endsWith("/")) "${request.data}$page/" else "${request.data}/$page/"
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        
        // Target the item container from your HTML snippet
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
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        // Grab title from the <a> title attribute or fallback to <img> alt
        val title = linkElement.attr("title").takeIf { it.isNotBlank() } 
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
            
        // Use data-original or data-webp since the site uses lazy-loading
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-webp")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:image") }
        )

        val isFolder = href.contains("/category/") || href.contains("/models/") || href.contains("/channels/")
        
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
        // Most tube sites use dashes or pluses for search queries
        val safeQuery = query.replace(" ", "-") 

        for (i in 1..3) {
            val url = if (i == 1) "$mainUrl/search/$safeQuery/" else "$mainUrl/search/$safeQuery/$i/"
            
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

        val title = document.selectFirst("h1")?.text()?.trim() 
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        
        // If it's a category/model page, scrape a few pages of videos
        if (url.contains("/category/") || url.contains("/models/") || url.contains("/channels/")) {
            val episodes = mutableListOf<Episode>()
            
            for (page in 1..10) {
                val pageUrl = if (page == 1) url else {
                    if (url.endsWith("/")) "$url$page/" else "$url/$page/"
                }

                try {
                    val pageDoc = if (page == 1) document else app.get(pageUrl, headers = defaultHeaders, timeout = 30).document
                    val pageEpisodes = pageDoc.select("div.item").mapNotNull { elem ->
                        val link = elem.selectFirst("a") ?: return@mapNotNull null
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        
                        val epTitle = link.attr("title").takeIf { it.isNotBlank() } 
                            ?: elem.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                            ?: "Video"
                            
                        val img = elem.selectFirst("img")
                        val epPoster = fixUrlNull(
                            img?.attr("data-original")?.takeIf { it.isNotBlank() }
                            ?: img?.attr("data-webp")?.takeIf { it.isNotBlank() }
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
        
        val tags = document.select("a.tag, .tags a").map { it.text().trim() }.filter { it.isNotBlank() }
        val recommendations = document.select("div.item").mapNotNull { it.toSearchResult() }

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
        
        // This regex targets the KVS flashvars block you provided in the HTML snippet
        val flashvarsRegex = Regex("""var flashvars\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val match = flashvarsRegex.find(html)
        
        if (match != null) {
            try {
                val jsonBlock = match.groupValues[1]
                
                // Extract video_url (usually 480p)
                val videoUrlMatch = Regex("""video_url\s*:\s*'(.*?)'""").find(jsonBlock)
                val videoUrl = videoUrlMatch?.groupValues?.get(1)?.replace("function/0/", "")
                val videoText = Regex("""video_url_text\s*:\s*'(.*?)'""").find(jsonBlock)?.groupValues?.get(1) ?: "480p"
                
                if (!videoUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name $videoText",
                            url = fixUrl(videoUrl),
                            type = INFER_TYPE
                        ) {
                            this.referer = data
                            // Parse quality from the text (e.g. '480p' -> 480)
                            this.quality = videoText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
                
                // Extract video_alt_url (usually 720p or HD)
                val altUrlMatch = Regex("""video_alt_url\s*:\s*'(.*?)'""").find(jsonBlock)
                val altUrl = altUrlMatch?.groupValues?.get(1)?.replace("function/0/", "")
                val altText = Regex("""video_alt_url_text\s*:\s*'(.*?)'""").find(jsonBlock)?.groupValues?.get(1) ?: "720p"
                
                if (!altUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name $altText",
                            url = fixUrl(altUrl),
                            type = INFER_TYPE
                        ) {
                            this.referer = data
                            this.quality = altText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
                
            } catch (e: Exception) {
                // Ignore parsing errors and fall back to the document parsers
            }
        }
        
        // Fallback 1: Iframe embed (if a video uses a third-party host)
        if (!foundLinks) {
            val document = org.jsoup.Jsoup.parse(html)
            document.select("iframe").mapNotNull { it.attr("src") }.forEach { src ->
                if (src.isNotBlank() && src.startsWith("http")) {
                    val extracted = loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                    if (extracted) foundLinks = true
                }
            }
            
            // Fallback 2: Standard HTML5 video tag
            if (!foundLinks) {
                document.select("video source, video.fp-engine").mapNotNull { it.attr("src") }.forEach { src ->
                    val cleanSrc = src.replace("function/0/", "")
                    if (cleanSrc.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "$name Direct",
                                url = fixUrl(cleanSrc),
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
        }

        return foundLinks
    }
