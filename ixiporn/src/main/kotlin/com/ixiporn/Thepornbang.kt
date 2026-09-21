package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Thepornbang : MainAPI() {
    override var mainUrl              = "https://www.thepornbang.com"
    override var name                 = "ThePornBang"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/videos_39/" to "New Videos",
        "${mainUrl}/top-rated_17/" to "Best Videos",
        "${mainUrl}/home41/" to "Recommended",
        "${mainUrl}/category/big-tits_c30/" to "Big Tits",
        "${mainUrl}/category/milf_c29/" to "MILF",
        "${mainUrl}/category/latina_c10/" to "Latina",
        "${mainUrl}/category/anal_c21/" to "Anal"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith("/")) "${request.data}$page/" else "${request.data}/$page/"
        }
        
        val document = app.get(url, referer = "$mainUrl/").document
        
        val isFolderShelf = request.name == "Models" || request.name == "Channels" || request.data.contains("/pornstar/") || request.data.contains("/studio/") || request.data.contains("/category/")
        
        val home = document.select("div.item").mapNotNull { it.toSearchResult(isFolderShelf) }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(isFolder: Boolean = false): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = this.selectFirst("span.text[itemprop='name']")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        // Maps Models, Channels, and Categories as TV Series (Folders)
        return if (isFolder || href.contains("/pornstar/") || href.contains("/studio/") || href.contains("/category/")) {
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

        for (i in 1..5) {
            val url = "${mainUrl}/search/$safeQuery/$i/"
            
            try {
                val document = app.get(url, referer = "$mainUrl/").document
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
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.title-name")?.text()?.trim() 
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("p.text-description[itemprop='description']")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        // 50-Page Pagination Scraper for Folders (Models, Studios, Categories)
        if (url.contains("/pornstar/") || url.contains("/studio/") || url.contains("/category/")) {
            val episodes = mutableListOf<Episode>()
            
            for (page in 1..50) {
                val pageUrl = if (page == 1) url else {
                    if (url.endsWith("/")) "${url}$page/" else "$url/$page/"
                }

                try {
                    val pageDoc = if (page == 1) document else app.get(pageUrl).document
                    val pageEpisodes = pageDoc.select("div.item").mapNotNull { elem ->
                        val link = elem.selectFirst("a.thumb") ?: return@mapNotNull null
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        
                        val epTitle = elem.selectFirst("span.text[itemprop='name']")?.text()?.trim() 
                            ?: elem.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                            ?: "Video"
                            
                        val epImg = elem.selectFirst("img")
                        val epPoster = fixUrlNull(
                            epImg?.attr("data-original")?.takeIf { it.isNotBlank() }
                            ?: epImg?.attr("src")?.takeIf { it.isNotBlank() }
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

        // Extracts Metadata for standard videos
        val tags = document.select("a.btn[href*='/tag/']").map { it.text().trim() }
        val actorsList = document.select("a[itemprop='actor'] span[itemprop='name']").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }
        val recommendations = document.select("#list_videos_related_right_items div.item").mapNotNull { it.toSearchResult() }

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
        val html = app.get(data, referer = "$mainUrl/").text
        var foundLinks = false
        
        // Isolates the KVS flashvars block
        val flashvarsBlock = Regex("""var\s+flashvars\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        
        if (flashvarsBlock != null) {
            // Hunts for all potential video URLs (video_url, video_alt_url, video_alt_url2, etc.)
            val urlsRegex = Regex("""(video(?:_alt)?_url\d*)\s*:\s*'([^']+)'""")
            val urlMatches = urlsRegex.findAll(flashvarsBlock)
            
            urlMatches.forEach { match ->
                val key = match.groupValues[1]
                val videoUrl = match.groupValues[2]
                
                // Matches the corresponding quality text label if the site provides it
                val textRegex = Regex("""${key}_text\s*:\s*'([^']+)'""")
                val textMatch = textRegex.find(flashvarsBlock)?.groupValues?.get(1) ?: ""
                
                val quality = when {
                    textMatch.contains("2160") || textMatch.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                    textMatch.contains("1080") -> Qualities.P1080.value
                    textMatch.contains("720") -> Qualities.P720.value
                    textMatch.contains("480") -> Qualities.P480.value
                    textMatch.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                // Positional arguments to ensure Gradle builds it without complaining about deprecated parameters!
                callback.invoke(
                    newExtractorLink(
                        name,
                        if (textMatch.isNotBlank()) "$name $textMatch" else name,
                        videoUrl,
                        data,
                        quality
                    )
                )
                foundLinks = true
            }
        }
        
        return foundLinks
    }
}
