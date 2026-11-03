package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class XMoviesForYou : MainAPI() {
    override var mainUrl              = "https://xmoviesforyou.com"
    override var name                 = "XMoviesForYou"
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

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Latest Videos",
        "${mainUrl}/studios/" to "Studios",
        "${mainUrl}/pornstars/" to "Pornstars"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) "${request.data}page/$page" else "${request.data}/page/$page"
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        
        // Targets the custom Tailwind data cards
        val home = document.select("a[data-video-card]").mapNotNull { it.toSearchResult() }

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
        val href = fixUrlNull(this.attr("href")) ?: return null
        val img = this.selectFirst("img")
        
        val title = this.selectFirst("h3")?.text()?.trim()
            ?: img?.attr("alt")?.trim()
            ?: return null
            
        val posterUrl = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("data-src")
        )

        // Maps Actors, Studios, and Categories to Folders if necessary, otherwise defaults to Movie
        val isFolder = href.contains("/category/") || href.contains("/tag/") || href.contains("/pornstar/") || href.contains("/studio/")
        
        return if (isFolder || href.contains("/pornstar") || href.contains("/model") || href.contains("/studio")) {
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
            val url = "${mainUrl}/search/$safeQuery/page/$i"
            
            try {
                val document = app.get(url, headers = defaultHeaders, timeout = 30).document
                val results = document.select("a[data-video-card]").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Video"
        val poster = fixUrlNull(document.selectFirst("div#player img")?.attr("src"))
        val description = document.selectFirst("div.prose p")?.text()?.trim()

        // Parses the 50-page folder logic for tags/actors/studios
        if (url.contains("/category/") || url.contains("/tag/") || url.contains("/pornstar/") || url.contains("/studio/")) {
            val episodes = mutableListOf<Episode>()
            
            for (page in 1..50) {
                val pageUrl = if (page == 1) url else {
                    if (url.endsWith("/")) "${url}page/$page" else "$url/page/$page"
                }

                try {
                    val pageDoc = if (page == 1) document else app.get(pageUrl, headers = defaultHeaders, timeout = 30).document
                    val pageEpisodes = pageDoc.select("a[data-video-card]").mapNotNull { elem ->
                        val epHref = fixUrlNull(elem.attr("href")) ?: return@mapNotNull null
                        val img = elem.selectFirst("img")
                        
                        val epTitle = elem.selectFirst("h3")?.text()?.trim()
                            ?: img?.attr("alt")?.trim()
                            ?: "Video"
                            
                        val epPoster = fixUrlNull(
                            img?.attr("src")?.takeIf { it.isNotBlank() }
                            ?: img?.attr("data-src")
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
                this.plot = description
            }
        }

        val tags = document.select("a[href*='/category/']").map { it.text().trim() }
        val actorsList = document.select("a[href*='/pornstar/']").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }
        val recommendations = document.select("div#related-posts-container a[data-video-card]").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = description
            this.tags = tags
            this.actors = actorsList
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = defaultHeaders, timeout = 30).text
        val document = org.jsoup.Jsoup.parse(html)
        var foundLinks = false
        
        // 1. Hunt for the un-obfuscated external host links (Streamtape, Mixdrop, Doodstream)
        val externalLinks = document.select("a[href^='https://streamtape'], a[href^='https://mxdrop'], a[href^='https://playmogo']")
        
        externalLinks.forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank()) {
                val extracted = loadExtractor(href, data, subtitleCallback, callback)
                if (extracted) foundLinks = true
            }
        }

        // 2. Fallback: Internal API Intercept for Native HLS (.m3u8) Streams
        // Scrapes the video ID from the page Javascript and hits their backend endpoint
        val videoIdMatch = Regex("""const\s+videoId\s*=\s*["']([^"']+)["']""").find(html)
        val targetMatch = Regex("""const\s+target\s*=\s*["']([^"']+)["']""").find(html)
        
        if (videoIdMatch != null) {
            val videoId = videoIdMatch.groupValues[1]
            val target = targetMatch?.groupValues?.get(1) ?: "dedi_1"
            
            try {
                val apiUrl = "$mainUrl/api/stream/$videoId?target=$target"
                val apiResponse = app.get(apiUrl, headers = defaultHeaders + mapOf("Referer" to data), timeout = 30).text
                
                // Using regex to avoid JVM 1.8 / 1.11 Jackson JSON parsing conflicts
                val videoUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(apiResponse)?.groupValues?.get(1)?.replace("\\/", "/")
                
                if (!videoUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name Native HLS",
                            url = videoUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = data
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                // Failsafe
            }
        }
        
        return foundLinks
    }
}
