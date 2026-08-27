package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Sxyland : MainAPI() {
    override var mainUrl              = "https://sxyland.com"
    override var name                 = "SxyLand"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/?filter=latest" to "Latest Videos",
        "${mainUrl}/?filter=most-viewed" to "Most Viewed",
        "${mainUrl}/?filter=popular" to "Top Rated",
        "${mainUrl}/?filter=longest" to "Longest Videos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Standard WordPress pagination format 
        val filterParams = request.data.substringAfter("?")
        val url = if (page == 1) {
            request.data
        } else {
            "${mainUrl}/page/$page/?$filterParams"
        }
        
        val document = app.get(url, referer = "$mainUrl/").document
        
        // Hunts for their specific grid layout
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
        
        val title = this.selectFirst("header.entry-header span")?.text()?.trim() 
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: return null
            
        // First checks the data-main-thumb attribute on the article, then falls back to the img tag
        val posterUrl = fixUrlNull(
            this.attr("data-main-thumb").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img.video-main-thumb")?.attr("src")
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
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() 
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: "Video"
            
        val description = document.selectFirst("div.video-description .desc")?.text()?.trim() 
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))

        // Scrapes metadata
        val tags = document.select("div.tags-list a").map { it.text().trim() }
        val actorsList = document.select("div#video-actors a").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }

        // Grabs recommendations from the bottom of the page
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
        val document = app.get(data, referer = "$mainUrl/").document
        var foundLinks = false
        
        // Hunts for the embed iframe (typically nowplay.to)
        val iframeSrc = document.selectFirst("div.responsive-player iframe")?.attr("src")
        
        if (!iframeSrc.isNullOrBlank()) {
            val fixedIframe = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
            
            // Primary method: Let Cloudstream's universal extractor handle it
            loadExtractor(fixedIframe, data, subtitleCallback, callback)
            foundLinks = true
            
            // Fallback Manual Scraper: Just in case the universal extractor fails
            try {
                val iframeHtml = app.get(fixedIframe, referer = data).text
                val mediaRegex = Regex("""(https?://[^"']+\.(?:mp4|m3u8)[^"']*)""")
                mediaRegex.findAll(iframeHtml).forEach { match ->
                    val isM3u8File = match.groupValues[1].contains(".m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name HD",
                            url = match.groupValues[1],
                            referer = fixedIframe,
                            quality = Qualities.Unknown.value,
                            isM3u8 = isM3u8File
                        )
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
