package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Fullhd : MainAPI() {
    override var mainUrl              = "https://www.fullhd.to"
    override var name                 = "Fullhd"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/latest-updates/" to "Latest Updates",
        "${mainUrl}/top-rated/" to "Top Rated",
        "${mainUrl}/most-popular/" to "Most Popular",
        "${mainUrl}/models/" to "Models",
        "${mainUrl}/sites/" to "Sites"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            request.data + "$page/"
        }
        
        val document = app.get(url).document
        val isFolderShelf = request.name == "Models" || request.name == "Sites"
        
        val home = document.select(".item, .video-item").mapNotNull { it.toSearchResult(isFolderShelf) }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(isFolderShelf: Boolean = false): SearchResponse? {
        val linkElement = if (this.tagName() == "a") this else this.selectFirst("a")
        if (linkElement == null) return null
        
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = this.selectFirst("strong.title")?.text()?.trim() 
            ?: linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: linkElement.text().trim()
            
        if (title.isEmpty()) return null
        
        val img = this.selectFirst("img.thumb") ?: this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-lazy")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-webp")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        val isFolder = isFolderShelf || href.contains("/models/") || href.contains("/sites/") || href.contains("/model/") || href.contains("/site/")

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

        for (i in 1..10) {
            val url = "${mainUrl}/search/$safeQuery/$i/"
            val document = app.get(url).document
            val results = document.select(".item, .video-item").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) searchResponse.addAll(results) else break
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst("img.thumb")?.attr("src")
        )
        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val isFolderLink = url.contains("/models/") || url.contains("/sites/") || url.contains("/model/") || url.contains("/site/")

        if (isFolderLink) {
            val episodes = mutableListOf<Episode>()
            var page = 1
            var hasNext = true
            
            // FIXED: Background loop that automatically scrapes up to 15 pages of videos!
            while (hasNext && page <= 15) {
                val pageUrl = if (page == 1) url else {
                    if (url.endsWith("/")) "${url}${page}/" else "${url}/${page}/"
                }
                
                try {
                    val doc = if (page == 1) document else app.get(pageUrl).document
                    
                    val items = doc.select(".item, .video-item").mapNotNull { elem ->
                        val link = if (elem.tagName() == "a") elem else elem.selectFirst("a")
                        if (link == null) return@mapNotNull null
                        
                        val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                        
                        if (epHref == url || epHref.contains("/models/") || epHref.contains("/sites/")) return@mapNotNull null

                        val epTitle = elem.selectFirst("strong.title")?.text()?.trim() 
                            ?: link.attr("title").takeIf { it.isNotBlank() } 
                            ?: elem.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                            ?: "Video"
                            
                        val epImg = elem.selectFirst("img.thumb") ?: elem.selectFirst("img")
                        val epPoster = fixUrlNull(
                            epImg?.attr("data-original")?.takeIf { it.isNotBlank() }
                            ?: epImg?.attr("data-src")?.takeIf { it.isNotBlank() }
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
                        // Anti-Loop Security: If the site redirects us back to page 1, kill the loop.
                        if (page > 1 && episodes.isNotEmpty() && items.first().data == episodes.first().data) {
                            hasNext = false
                        } else {
                            episodes.addAll(items)
                        }
                    }
                } catch (e: Exception) {
                    hasNext = false // Kill the loop on network error (like a 404 Page Not Found)
                }
                
                page++
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val sources = document.select("source")
        var foundLinks = false
        
        for (source in sources) {
            val src = source.attr("src")
            val label = source.attr("label") ?: ""
            
            if (src.isNotBlank()) {
                foundLinks = true
                val quality = when {
                    label.contains("2160") -> Qualities.P2160.value
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
                        url = src,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to data, "Origin" to mainUrl)
                        this.quality = quality
                    }
                )
            }
        }
        
        if (!foundLinks) {
            val videoUrl = document.selectFirst("video")?.attr("src")
            if (!videoUrl.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to data, "Origin" to mainUrl)
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }

        return true
    }
}
