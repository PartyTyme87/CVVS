package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FxPornHD : MainAPI() {
    override var mainUrl              = "https://fxpornhd.com"
    override var name                 = "FxPornHD"
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
        "${mainUrl}/?filter=latest" to "Newest",
        "${mainUrl}/?filter=popular" to "Best",
        "${mainUrl}/?filter=most-viewed" to "Most Viewed",
        "${mainUrl}/?filter=longest" to "Longest",
        "${mainUrl}/channels/" to "Channels",
        "${mainUrl}/brazzers/" to "Brazzers",
        "${mainUrl}/bangbros/" to "BangBros",
        "${mainUrl}/realitykings/" to "RealityKings",
        "${mainUrl}/pervmom/" to "PervMom"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?filter=")) {
                request.data.replace("?", "page/$page/?")
            } else {
                if (request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
            }
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        val home = document.select("article.thumb-block").mapNotNull { it.toSearchResult() }

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
        
        val title = this.selectFirst("span.title")?.text()?.trim()
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: linkElement.attr("data-title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        val isFolder = href.contains("/category/") || href.contains("/tag/") || href.contains("/actor/") || href.matches(Regex(".*/(?:channels|brazzers|bangbros|realitykings|pervmom)/.*"))
        
        return if (isFolder || this.hasClass("category-channels")) {
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
            val url = if (i == 1) {
                "${mainUrl}/?s=$safeQuery"
            } else {
                "${mainUrl}/page/$i/?s=$safeQuery"
            }
            
            try {
                val document = app.get(url, headers = defaultHeaders, timeout = 30).document
                val results = document.select("article.thumb-block").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("h1[itemprop='name']")?.text()?.trim() 
            ?: document.selectFirst("meta[itemprop='name']")?.attr("content")?.trim() 
            ?: document.selectFirst("h1.widget-title")?.text()?.trim()
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[itemprop='thumbnailUrl']")?.attr("content"))
        val description = document.selectFirst("div.video-description div.desc p")?.text()?.trim() 
            ?: document.selectFirst("meta[itemprop='description']")?.attr("content")?.trim()

        if (url.contains("/category/") || url.contains("/tag/") || url.contains("/actor/") || document.selectFirst("h1.widget-title") != null) {
            val episodes = mutableListOf<Episode>()
            
            for (page in 1..50) {
                val pageUrl = if (page == 1) url else {
                    if (url.endsWith("/")) "${url}page/$page/" else "$url/page/$page/"
                }

                try {
                    val pageDoc = if (page == 1) document else app.get(pageUrl, headers = defaultHeaders, timeout = 30).document
                    val pageEpisodes = pageDoc.select("article.thumb-block").mapNotNull { elem ->
                        val link = elem.selectFirst("a") ?: return@mapNotNull null
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        
                        val epTitle = elem.selectFirst("span.title")?.text()?.trim()
                            ?: link.attr("title").takeIf { it.isNotBlank() }
                            ?: elem.selectFirst("img")?.attr("alt")?.trim()
                            ?: "Video"
                            
                        val img = elem.selectFirst("img")
                        val epPoster = fixUrlNull(
                            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
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

        val tags = document.select("div.tags-list a.label").map { it.text().trim() }
        val actorsList = document.select("div.tags-list a[href*='/actor/']").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }
        val recommendations = document.select("div.under-video-block article.thumb-block").mapNotNull { it.toSearchResult() }

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
        
        // FxPornHD uses the responsive-player wrapper for its WatchStreamUs embeds
        val iframeSrc = document.selectFirst("div.responsive-player iframe, div.video-embed iframe")?.attr("src")
        
        if (!iframeSrc.isNullOrBlank()) {
            val fixedIframe = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
            
            // 1. Try Cloudstream's native extractors first
            foundLinks = loadExtractor(fixedIframe, data, subtitleCallback, callback)
            
            // 2. Comprehensive WatchStreamUs & Custom Player Fallback
            if (!foundLinks) {
                try {
                    // Fetch the iframe content, explicitly passing the FxPornHD referer
                    val iframeResponse = app.get(fixedIframe, headers = defaultHeaders + mapOf("Referer" to data), timeout = 30)
                    val iframeHtml = iframeResponse.text
                    val unpackedHtml = JsUnpacker(iframeHtml).unpack() ?: iframeHtml
                    val cleanHtml = unpackedHtml.replace("\\/", "/")
                    
                    val iframeDoc = org.jsoup.Jsoup.parse(cleanHtml)
                    val originHost = Regex("""^(https?://[^/]+)""").find(iframeResponse.url)?.groupValues?.get(1) ?: "https://player.watchstreamus.com"
                    
                    // A. Check for inner iframes (WatchStreamUs might just be a wrapper for Streamtape, MixDrop, or Doodstream)
                    iframeDoc.select("iframe").mapNotNull { it.attr("src") }.forEach { innerIframe ->
                        if (innerIframe.isNotBlank()) {
                            val fixedInner = fixUrl(if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe)
                            val extracted = loadExtractor(fixedInner, fixedIframe, subtitleCallback, callback)
                            if (extracted) foundLinks = true
                        }
                    }
                    
                    // B. Direct <source> tags embedded inside the iframe
                    if (!foundLinks) {
                        iframeDoc.select("video source").mapNotNull { it.attr("src") }.forEach { src ->
                            if (src.isNotBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name HD",
                                        url = fixUrl(src),
                                        type = INFER_TYPE
                                    ) {
                                        this.referer = data // Top-level referer is often required by secure CDNs
                                        this.headers = mapOf(
                                            "Referer" to data,
                                            "Origin" to originHost
                                        )
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundLinks = true
                            }
                        }
                    }

                    // C. Deep Regex Fallback for obfuscated file:/src: links and raw .mp4/.m3u8 URLs
                    if (!foundLinks) {
                        val mediaRegex = Regex("""(?:file|src|url)\s*:\s*["'](https?://[^"']+)["']|(https?://[^"'\s,;]+\.(?:m3u8|mp4)[^"'\s,;]*)""")
                        mediaRegex.findAll(cleanHtml).forEach { match ->
                            val rawUrl = match.groupValues[1].takeIf { it.isNotBlank() } ?: match.groupValues[2]
                            
                            if (rawUrl.isNotBlank() && (rawUrl.contains(".mp4") || rawUrl.contains(".m3u8") || rawUrl.contains("/hls/"))) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name HD",
                                        url = rawUrl,
                                        type = INFER_TYPE
                                    ) {
                                        this.referer = data
                                        this.headers = mapOf(
                                            "Referer" to data,
                                            "Origin" to originHost
                                        )
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundLinks = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Ignore fallback errors to prevent crashes
                }
            }
        }
        
        return foundLinks
    }
}
