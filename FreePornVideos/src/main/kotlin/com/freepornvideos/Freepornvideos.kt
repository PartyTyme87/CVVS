package com.freepornvideos

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Freepornvideos : MainAPI() {
    override var mainUrl              = "https://www.freepornvideos.xxx"
    override var name                 = "Freepornvideos"
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
        "$mainUrl/videos/" to "Latest",
        "$mainUrl/videos/top-rated/" to "Top Rated",
        "$mainUrl/most-popular/week/" to "Popular",
        "$mainUrl/models/violet-myers/" to "Violet Myers",
        "$mainUrl/models/rae-lil-black/" to "Rae Lil Black",
        "$mainUrl/models/alexis-fawx/" to "Alexis Fawx",
        "$mainUrl/models/willow-ryder/" to "Willow Ryder",
        "$mainUrl/models/sofia-lee/" to "Sofia Lee",
        "$mainUrl/models/little-puck/" to "Little Puck",
        "$mainUrl/models/ashlyn-peaks/" to "Ashlyn Peaks",
        "$mainUrl/models/kira-perez/" to "Kira Perez",
        "$mainUrl/categories/big-tits/" to "Big Tits",
        "$mainUrl/sites/my-pervy-family/" to "My Pervy Family",
        "$mainUrl/sites/milfy/" to "Milfy",
        "$mainUrl/sites/my-friends-hot-mom/" to "My Friend's Hot Mom",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Standard URL pagination appending
            if (request.data.endsWith("/")) "${request.data}$page/" else "${request.data}/$page/"
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        
        // Target the video item blocks from the provided HTML
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
        
        // Extract title from the anchor tag or the strong.title element
        val title = this.selectFirst("strong.title")?.text()?.trim()
            ?: linkElement.attr("title").takeIf { it.isNotBlank() } 
            ?: return null
            
        // Extract poster from the data-src or src attribute
        val img = this.selectFirst("img.thumb")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:image") }
        )

        // Check if the link points to a model or channel (folder)
        val isFolder = href.contains("/models/") || href.contains("/channels/") || href.contains("/categories/") || href.contains("/sites/")
        
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
        val safeQuery = query.replace(" ", "-") // Site uses dash-separated search query URLs

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
        
        // Handle model/category pages by scraping multiple pages of video grids
        if (url.contains("/models/") || url.contains("/channels/") || url.contains("/categories/") || url.contains("/sites/")) {
            val episodes = mutableListOf<Episode>()
            
            for (page in 1..25) {
                val pageUrl = if (page == 1) url else {
                    if (url.endsWith("/")) "$url$page/" else "$url/$page/"
                }

                try {
                    val pageDoc = if (page == 1) document else app.get(pageUrl, headers = defaultHeaders, timeout = 30).document
                    val pageEpisodes = pageDoc.select("div.item").mapNotNull { elem ->
                        val link = elem.selectFirst("a") ?: return@mapNotNull null
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        
                        // Ignore model links inside the item on a model page
                        if (epHref.contains("/models/")) return@mapNotNull null

                        val epTitle = elem.selectFirst("strong.title")?.text()?.trim()
                            ?: link.attr("title").takeIf { it.isNotBlank() } 
                            ?: "Video"
                            
                        val img = elem.selectFirst("img.thumb")
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
        
        // Tag extraction
        val tags = document.select("a.tag-item, .tags-list a").map { it.text().trim() }.filter { it.isNotBlank() }
        
        // Actor extraction
        val actorsList = document.select("a.models__item, .models-list a").mapNotNull { elem ->
            val name = elem.select("span").text().trim().ifBlank { elem.text().trim() }
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }

        val recommendations = document.select("div.item").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.tags = tags
            this.actors = actorsList
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = defaultHeaders, timeout = 30).text
        val document = org.jsoup.Jsoup.parse(html)
        var foundLinks = false
        
        // Strategy 1: Check for iframe embeds (common for tube sites)
        document.select("iframe").mapNotNull { it.attr("src") }.forEach { src ->
            if (src.isNotBlank() && src.startsWith("http")) {
                val extracted = loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                if (extracted) foundLinks = true
            }
        }
        
        // Strategy 2: Check for native HTML5 video tags
        if (!foundLinks) {
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

        // Strategy 3: Check for embedded player JSON configuration (often assigned to window or flashvars)
        if (!foundLinks) {
             val videoUrlMatch = Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)"""").find(html) 
                 ?: Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(html)
                 ?: Regex("""src\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(html)

             val videoUrl = videoUrlMatch?.groupValues?.get(1)?.replace("\\/", "/")
             
             if (!videoUrl.isNullOrBlank()) {
                 callback.invoke(
                     newExtractorLink(
                         source = name,
                         name = "$name Direct",
                         url = fixUrl(videoUrl),
                         type = INFER_TYPE
                     ) {
                         this.referer = data
                         this.quality = Qualities.Unknown.value
                     }
                 )
                 foundLinks = true
             }
        }

        return foundLinks
    }
}
