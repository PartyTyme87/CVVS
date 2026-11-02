package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class XXXParodyHD : MainAPI() {
    override var mainUrl              = "https://xxxparodyhd.net"
    override var name                 = "XXXParodyHD"
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
        "${mainUrl}/" to "Latest Movies",
        "${mainUrl}/most-viewed/" to "Most Viewed",
        "${mainUrl}/most-rating/" to "Top Rated",
        "${mainUrl}/parody-genres/" to "Genres",
        "${mainUrl}/parody-pornstars/" to "Pornstars",
        "${mainUrl}/parody-studios/" to "Studios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) {
                request.data.replace("?", "page/$page/?")
            } else {
                if (request.data.endsWith("/")) "${request.data}page/$page/" else "${request.data}/page/$page/"
            }
        }
        
        val document = app.get(url, headers = defaultHeaders, timeout = 30).document
        
        val home = document.select("article.card, article.spot__i").mapNotNull { it.toSearchResult() }

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
        
        val img = this.selectFirst("img")
        
        val title = this.selectFirst("h2.card__t, h3.card__t, span.spot__t")?.text()?.trim()
            ?: img?.attr("alt")?.replace("Watch", "", true)?.replace("Porn Online Free", "", true)?.trim()
            ?: return null
            
        val posterUrl = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
        )

        val isFolder = href.contains("/director/") || href.contains("/genre/") || href.contains("/actor/") || href.contains("/pornstar/")
        
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
            val url = if (i == 1) {
                "${mainUrl}/?s=$safeQuery"
            } else {
                "${mainUrl}/page/$i/?s=$safeQuery"
            }
            
            try {
                val document = app.get(url, headers = defaultHeaders, timeout = 30).document
                val results = document.select("article.card, article.spot__i").mapNotNull { it.toSearchResult() }

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
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.replace(" - XXXParodyHD", "")?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("div.desc")?.text()?.trim() 
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        if (url.contains("/director/") || url.contains("/genre/") || url.contains("/actor/") || url.contains("/pornstar/")) {
            val episodes = mutableListOf<Episode>()
            
            for (page in 1..50) {
                val pageUrl = if (page == 1) url else {
                    if (url.endsWith("/")) "${url}page/$page/" else "$url/page/$page/"
                }

                try {
                    val pageDoc = if (page == 1) document else app.get(pageUrl, headers = defaultHeaders, timeout = 30).document
                    val pageEpisodes = pageDoc.select("article.card, article.spot__i").mapNotNull { elem ->
                        val link = elem.selectFirst("a") ?: return@mapNotNull null
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        
                        val img = elem.selectFirst("img")
                        val epTitle = elem.selectFirst("h2.card__t, h3.card__t, span.spot__t")?.text()?.trim()
                            ?: img?.attr("alt")?.replace("Watch", "", true)?.replace("Porn Online Free", "", true)?.trim()
                            ?: "Video"
                            
                        val epPoster = fixUrlNull(
                            img?.attr("src")?.takeIf { it.isNotBlank() }
                            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
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

        val tags = document.select("a.chip[href*='/genre/']").map { it.text().trim() }
        val actorsList = document.select("a.chip[href*='/pornstar/']").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }
        val recommendations = document.select("div.grid article.card, article.spot__i").mapNotNull { it.toSearchResult() }

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
        
        // 1. Target the HLM data-servers JSON array
        val serverJsonRaw = document.selectFirst("section.hlm")?.attr("data-servers")
        
        if (!serverJsonRaw.isNullOrBlank()) {
            try {
                // Replaced AppUtils.parseJson with Regex to avoid JVM Target 1.8/11 compiler mismatches
                val urlRegex = Regex(""""u"\s*:\s*"([^"]+)"""")
                urlRegex.findAll(serverJsonRaw).forEach { match ->
                    val serverUrl = match.groupValues[1].replace("\\/", "/")
                    if (serverUrl.isNotBlank()) {
                        val fixedUrl = fixUrl(serverUrl)
                        val extracted = loadExtractor(fixedUrl, data, subtitleCallback, callback)
                        if (extracted) foundLinks = true
                    }
                }
            } catch (e: Exception) {
                // Failsafe
            }
        }
        
        // 2. Fallback: Standard iframe search just in case the JSON is missing
        if (!foundLinks) {
            val iframes = document.select("iframe").mapNotNull { it.attr("src") }
                .filter { it.isNotBlank() && !it.contains("displayvertising") && !it.contains("a-ads") }
                
            iframes.forEach { iframeSrc ->
                val fixedIframe = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
                val extracted = loadExtractor(fixedIframe, data, subtitleCallback, callback)
                if (extracted) foundLinks = true
            }
        }
        
        return foundLinks
    }
}
