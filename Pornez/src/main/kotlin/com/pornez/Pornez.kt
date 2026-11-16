package com.pornez

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Pornez : MainAPI() {
    override var mainUrl              = "https://pornez.cam"
    override var name                 = "Pornez"
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
        "${mainUrl}/home?range=month" to "Popular",
        "${mainUrl}/now" to "Watching Now",
        "${mainUrl}/home?range=explore" to "Explore",
        "${mainUrl}/home?range=recent" to "Recent",
        "${mainUrl}/?s=Sexmex" to "Sexmex",
        "${mainUrl}/?s=Rkprime" to "RK Prime",
        "${mainUrl}/?s=Milf" to "Milf",
        "${mainUrl}/?s=Violet+Myers" to "Violet Myers"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // WordPress style pagination
            if (request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        
        // Target the WordPress article blocks
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
        
        // Extract title from data-title attribute or fallback to entry-header text
        val title = linkElement.attr("data-title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("header.entry-header span")?.text()?.trim() 
            ?: return null
            
        // Extract poster from data-main-thumb attribute or the img tag
        val posterUrl = fixUrlNull(
            this.attr("data-main-thumb").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:image") }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..3) {
            // WordPress search pagination
            val url = if (i == 1) "$mainUrl/?s=$safeQuery" else "$mainUrl/page/$i/?s=$safeQuery"
            
            try {
                val document = app.get(url, headers = defaultHeaders, timeout = 30).document
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
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document

        // Get title from the main heading
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        
        // Find tags based on standard WP category/tag links
        val tags = document.select("a[rel='tag']").map { it.text().trim() }
        val recommendations = document.select("article.loop-video").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, headers = defaultHeaders, timeout = 30).text
        val document = org.jsoup.Jsoup.parse(html)
        var foundLinks = false
        
        // 1. Check for standard external embed iframes (Streamtape, Dood, etc)
        document.select("iframe").mapNotNull { it.attr("src") }.forEach { src ->
            if (src.isNotBlank()) {
                val extracted = loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                if (extracted) foundLinks = true
            }
        }
        
        // 2. Check for native HTML5 video tags
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

        // 3. Fallback: Many WP adult themes store the raw embed URL in a specific data attribute
        if (!foundLinks) {
            val embedDiv = document.selectFirst("div[data-embed]")
            val embedCode = embedDiv?.attr("data-embed")
            if (!embedCode.isNullOrBlank()) {
                // If it contains an iframe code, extract the src
                val srcMatch = Regex("""src=["']([^"']+)["']""").find(embedCode)
                val embedUrl = srcMatch?.groupValues?.get(1)
                
                if (embedUrl != null) {
                     val extracted = loadExtractor(fixUrl(embedUrl), data, subtitleCallback, callback)
                     if (extracted) foundLinks = true
                }
            }
        }
        
        return foundLinks
    }
}
