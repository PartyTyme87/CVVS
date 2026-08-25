package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Siska : MainAPI() {
    override var mainUrl              = "https://siska.video"
    override var name                 = "Siska"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/best_xvideos.php" to "Best Videos",
        "${mainUrl}/"                 to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Siska uses ?page=2 for pagination on its best_xvideos page
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }
        
        val document = app.get(url, referer = "$mainUrl/").document
        
        // Hunts for their specific grid layout using the 'pure-u' classes
        val home = document.select("li[class*=pure-u]").mapNotNull { it.toSearchResult() }

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
        val linkElement = this.selectFirst("a.video-thumb") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = this.selectFirst("h3.title_desc")?.text()?.trim() 
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..3) {
            // Siska uses a standard search.php?s=query format
            val url = "${mainUrl}/search.php?s=$safeQuery&page=$i"
            
            try {
                val document = app.get(url, referer = "$mainUrl/").document
                val results = document.select("li[class*=pure-u]").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("h1[itemprop='name']")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val description = document.selectFirst("div.video-description")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst("meta[itemprop='thumbnailUrl']")?.attr("content"))

        // Extracts categories/tags
        val tags = document.select("p:contains(Categories:) a, li:contains(Categories:) a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            
        // Extracts actors 
        val actorsList = document.select("p:contains(Actress:) a, li:contains(Actress:) a").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }

        val recommendations = document.select("ul#video-list li").mapNotNull { it.toSearchResult() }

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
        
        // Finds all third-party iframes on the video page
        val iframes = document.select("div.videoholder iframe, iframe").mapNotNull { it.attr("src") }
        
        for (iframeSrc in iframes) {
            if (iframeSrc.isNotBlank()) {
                val iframeUrl = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
                
                // Passes the third-party URL to Cloudstream's universal extractor!
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}
