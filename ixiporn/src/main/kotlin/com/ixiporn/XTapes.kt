package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class XTapes : MainAPI() {
    override var mainUrl              = "https://xtapes.la"
    override var name                 = "XTapes"
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
        "${mainUrl}/104363/" to "4K",
        "${mainUrl}/796584/" to "Anal",
        "${mainUrl}/792684/" to "Big Tits",
        "${mainUrl}/352895/" to "CreamPie",
        "${mainUrl}/584139/" to "Interracial",
        "${mainUrl}/438706/" to "MILFs",
        "${mainUrl}/48048/" to "Parodies",
        "${mainUrl}/126957/" to "Teen"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        val home = document.select("ul.listing-tube li.border-radius-5").mapNotNull { it.toSearchResult() }

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
        val linkElement = this.selectFirst("a[href*='/video/']") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = linkElement.selectFirst("h3 strong")?.text()?.trim()
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.trim() 
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
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
                val document = app.get(url, headers = defaultHeaders, timeout = 30).document
                val results = document.select("ul.listing-tube li.border-radius-5").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("h1[itemprop='name'] span, h1[itemprop='name']")?.text()?.trim() 
            ?: document.selectFirst("meta[itemprop='name']")?.attr("content")?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[itemprop='thumbnailUrl']")?.attr("content"))
        
        // XTapes includes plot details in a dedicated infotext block
        val description = document.selectFirst("div.infotext[itemprop='description'] p")?.text()?.trim() 
            ?: document.selectFirst("meta[itemprop='description']")?.attr("content")?.trim()

        val tags = document.select("div#cat-tag ul li a").map { it.text().replace("[", "").replace("]", "").trim() }
        
        val actorsList = document.select("span[itemprop='actor'] meta[itemprop='name']").mapNotNull { elem ->
            val name = elem.attr("content").trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }
        
        val recommendations = document.select("div.sidebar-widget ul.listing-tube li").mapNotNull { it.toSearchResult() }

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
        val document = app.get(data, headers = defaultHeaders, timeout = 30).document
        var foundLinks = false
        
        // XTapes embeds videos via a secondary host (sextapes.to) inside an iframe
        val iframeSrc = document.selectFirst("div.video-embed iframe")?.attr("src")
        
        if (!iframeSrc.isNullOrBlank()) {
            val fixedIframe = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
            
            // Allow Cloudstream to natively process known hosts
            loadExtractor(fixedIframe, data, subtitleCallback, callback)
            foundLinks = true
            
            // Fallback: Extracts the underlying video link if sextapes.to blocks the default extractor
            try {
                val iframeHtml = app.get(fixedIframe, headers = defaultHeaders + mapOf("Referer" to data), timeout = 30).text
                val unpackedHtml = JsUnpacker(iframeHtml).unpack() ?: iframeHtml
                val cleanHtml = unpackedHtml.replace("\\/", "/")
                
                val mediaRegex = Regex("""(https?://[^"'\s,;]+\.(?:m3u8|mp4)[^"'\s,;]*)""")
                mediaRegex.findAll(cleanHtml).forEach { match ->
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
