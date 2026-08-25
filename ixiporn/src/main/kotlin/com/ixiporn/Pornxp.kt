package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Pornxp : MainAPI() {
    // The HTML explicitly states porn-xp.eu is their new backup domain, which is usually the most stable to scrape
    override var mainUrl              = "https://porn-xp.eu" 
    override var name                 = "PornXP"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "New Videos",
        "${mainUrl}/best/" to "Best Videos",
        "${mainUrl}/released/" to "New Releases",
        "${mainUrl}/hd/" to "HD"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Handles pagination like /?page=2 or /best/?page=2
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }
        
        val document = app.get(url, referer = "$mainUrl/").document
        
        // Hunts for their specific grid layout
        val home = document.select("div.item_cont").mapNotNull { it.toSearchResult() }

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
        
        val title = this.selectFirst("div.item_title")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
            
        val img = this.selectFirst("img.item_img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        // If it's a tag folder, treat it as a TvSeries so Cloudstream can open it as a shelf
        val isFolder = href.contains("/tags/")

        return if (isFolder) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = posterUrl
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..5) {
            // Uses their built-in search parameter format
            val url = "${mainUrl}/?q=$safeQuery&page=$i"
            
            try {
                val document = app.get(url, referer = "$mainUrl/").document
                val results = document.select("div.item_cont").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("div.player_details h1")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val description = document.selectFirst("div#desc")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst("video#player")?.attr("poster"))

        // Grabs all the metadata bubbles
        val tags = document.select("div.tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val isFolderLink = url.contains("/tags/")

        if (isFolderLink) {
            val episodes = mutableListOf<Episode>()
            var page = 1
            var hasNext = true
            
            while (hasNext && page <= 15) {
                val pageUrl = if (page == 1) url else {
                    if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
                }
                
                try {
                    val doc = if (page == 1) document else app.get(pageUrl).document
                    
                    val items = doc.select("div.item_cont").mapNotNull { elem ->
                        val link = elem.selectFirst("a") ?: return@mapNotNull null
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        
                        if (epHref == url || epHref.contains("/tags/")) return@mapNotNull null

                        val epTitle = elem.selectFirst("div.item_title")?.text()?.trim() 
                            ?: elem.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                            ?: "Video"
                            
                        val epImg = elem.selectFirst("img.item_img")
                        val epPoster = fixUrlNull(
                            epImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                            ?: epImg?.attr("src")?.takeIf { it.isNotBlank() }
                        )
                        
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.posterUrl = epPoster
                        }
                    }
                    
                    if (items.isEmpty()) {
                        hasNext = false
                    } else {
                        if (page > 1 && episodes.isNotEmpty() && items.first().data == episodes.first().data) {
                            hasNext = false
                        } else {
                            episodes.addAll(items)
                        }
                    }
                } catch (e: Exception) {
                    hasNext = false
                }
                
                page++
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }

        // Grabs the related videos from the bottom of the page
        val recommendations = document.select("div.item_cont").mapNotNull { elem ->
            val link = elem.selectFirst("a") ?: return@mapNotNull null
            val recHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            
            if (recHref == url) return@mapNotNull null

            val recTitle = elem.selectFirst("div.item_title")?.text()?.trim() ?: link.text().trim()
            val recImg = elem.selectFirst("img.item_img")
            val recPoster = fixUrlNull(
                recImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: recImg?.attr("src")?.takeIf { it.isNotBlank() }
            )
            
            newMovieSearchResponse(recTitle, recHref, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        
        // This targets the specific video player sources you found!
        val sources = document.select("video#player source")
        
        for (source in sources) {
            val src = source.attr("src")
            val label = source.attr("title").takeIf { it.isNotBlank() } ?: source.attr("label") ?: ""
            
            if (src.isNotBlank()) {
                val quality = when {
                    label.contains("2160") || label.contains("4k", ignoreCase = true) -> Qualities.P2160.value
                    label.contains("1080") -> Qualities.P1080.value
                    label.contains("720") -> Qualities.P720.value
                    label.contains("480") -> Qualities.P480.value
                    label.contains("360") -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} $label".trim(),
                        url = fixUrl(if (src.startsWith("//")) "https:$src" else src),
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to data, "Origin" to mainUrl)
                        this.quality = quality
                    }
                )
            }
        }

        return true
    }
}
