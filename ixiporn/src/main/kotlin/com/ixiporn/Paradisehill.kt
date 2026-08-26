package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Paradisehill : MainAPI() {
    override var mainUrl              = "https://en.paradisehill.cc"
    override var name                 = "ParadiseHill"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    // Maps out the diverse categories and studios you found in the HTML
    override val mainPage = mainPageOf(
        "${mainUrl}/all/?sort=created_at" to "All Films",
        "${mainUrl}/popular/?filter=all&sort=by_likes" to "Popular",
        "${mainUrl}/categories/" to "Categories",
        "${mainUrl}/studios/?sort=by_likes" to "Studios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            // Properly handles pagination with their parameter structure
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }
        
        val document = app.get(url, referer = "$mainUrl/").document
        
        // Hunts for both videos (.list-film-item) and standard folders (.item)
        val home = document.select(".item").mapNotNull { it.toSearchResult() }

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
        
        // Hunts for the title inside the specific schema tags
        val title = this.selectFirst("span[itemprop='name']")?.text()?.trim() 
            ?: this.selectFirst("div[itemprop='name'] span")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        // Determines if this is a Category/Studio folder or an actual video
        val isFolder = href.contains("/category/") || href.contains("/categories/") || href.contains("/studio/") || href.contains("/studios/")

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

        for (i in 1..5) {
            // what=1 restricts the search strictly to films
            val url = "${mainUrl}/search/?pattern=$safeQuery&what=1&page=$i"
            
            try {
                val document = app.get(url, referer = "$mainUrl/").document
                val results = document.select(".item.list-film-item").mapNotNull { it.toSearchResult() }

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

        // Parses the structured schema tags for metadata
        val title = document.selectFirst("h1.title-inside")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val description = document.selectFirst("span[itemprop='description']")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst("img[itemprop='thumbnailUrl']")?.attr("src") ?: document.selectFirst("img[itemprop='image']")?.attr("src"))

        val tags = document.select("span[itemprop='genre'] a").map { it.text().trim() }.filter { it.isNotBlank() }
        val actorsList = document.select("span[itemprop='director'] a, span[itemprop='actor'] a").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }

        // Grabs similar films from the recommendations tab
        val recommendations = document.select("div#home .item.list-film-item").mapNotNull { it.toSearchResult() }

        // Category & Studio pagination handler
        val isFolderLink = url.contains("/category/") || url.contains("/studio/")
        if (isFolderLink) {
            val items = document.select(".item.list-film-item").mapNotNull { it.toSearchResult() }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, items.filterIsInstance<Episode>()) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }

        // MAGIC TRICK: Extracts the split video parts from the hidden 'videoList' variable
        val scriptData = document.select("script:containsData(var videoList)").firstOrNull()?.data()
        val videoParts = mutableListOf<String>()
        
        if (scriptData != null) {
            val srcRegex = Regex(""""src":"([^"]+)"""")
            srcRegex.findAll(scriptData).forEach { match ->
                // Cleans the escaped slashes (e.g. \/video\/) into valid URLs
                val mp4Link = match.groupValues[1].replace("\\/", "/")
                if (!videoParts.contains(mp4Link)) {
                    videoParts.add(mp4Link)
                }
            }
        }

        // If the video is split into parts, we return it as a TvSeries so Cloudstream plays them in sequence
        if (videoParts.size > 1) {
            val episodes = videoParts.mapIndexed { index, mp4Link ->
                newEpisode(mp4Link) {
                    this.name = "Part ${index + 1}"
                    this.episode = index + 1
                }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = description
                this.tags = tags
                this.actors = actorsList
                this.recommendations = recommendations
            }
        }

        // Standard single-part movie response
        val finalUrl = if (videoParts.isNotEmpty()) videoParts.first() else url
        return newMovieLoadResponse(title, finalUrl, TvType.NSFW, finalUrl) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = description
            this.tags = tags
            this.actors = actorsList
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // If our load function successfully grabbed the direct .mp4 link, pass it straight to the player!
        if (data.contains(".mp4")) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = false
                )
            )
            return true
        }
        
        // Fallback: Scrapes the page if we didn't intercept the mp4 early
        val document = app.get(data, referer = "$mainUrl/").document
        var foundLinks = false
        
        val sources = document.select("video source")
        for (source in sources) {
            val src = source.attr("src")
            if (src.isNotBlank() && src.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(if (src.startsWith("//")) "https:$src" else src),
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = false
                    )
                )
                foundLinks = true
            }
        }

        return foundLinks
    }
}
