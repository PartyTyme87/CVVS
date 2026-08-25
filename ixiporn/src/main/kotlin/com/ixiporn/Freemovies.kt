package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Freemovies : MainAPI() {
    override var mainUrl              = "https://freemovies.lol"
    override var name                 = "Free Movies Center"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/" to "Home",
        "${mainUrl}/category/movies/" to "Movies",
        "${mainUrl}/category/tv-series/" to "TV Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Standard WordPress pagination formatting
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, referer = "$mainUrl/").document
        
        // Hunts for their specific grid layout (.item.post)
        val home = document.select("div.item.post").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = this.selectFirst(".name")?.text()?.trim() 
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        // Detects if it's a TV show by looking for season/episode badges in the meta tags
        val isTvSeries = this.selectFirst(".meta .type")?.text()?.contains("SS", ignoreCase = true) == true

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..5) {
            val url = "${mainUrl}/page/$i/?s=$safeQuery"
            
            try {
                val document = app.get(url, referer = "$mainUrl/").document
                val results = document.select("div.item.post").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("h1.name")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Movie"
            
        val description = document.selectFirst("div.description p")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst("img[itemprop='image']")?.attr("data-src"))

        // Scrapes metadata
        val tags = document.select("div.tags a").map { it.text().trim() }
        val actorsList = document.select("div.casts a").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }

        // Grabs recommendations from the sidebar
        val recommendations = document.select("div.related.items a.item").mapNotNull { elem ->
            val recHref = fixUrlNull(elem.attr("href")) ?: return@mapNotNull null
            if (recHref == url) return@mapNotNull null

            val recTitle = elem.selectFirst(".name")?.text()?.trim() ?: "Video"
            val recPoster = fixUrlNull(elem.selectFirst("img")?.attr("data-src"))
            
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        // Return a standard Movie LoadResponse. 
        // Note: For TV Shows, they likely load an episode list via API, but we pass the main link to the extractor for now!
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        
        // This targets the hidden base64 script you discovered in the HTML!
        val base64Script = document.selectFirst("script#servers-js-extra")?.attr("src")
        
        if (base64Script != null && base64Script.contains("base64,")) {
            val encodedData = base64Script.substringAfter("base64,")
            
            try {
                // Decodes the base64 string back into readable JSON/Text
                val decodedString = String(android.util.Base64.decode(encodedData, android.util.Base64.DEFAULT))
                
                // Rips out the specific server URLs using Regex
                val serverRegex = Regex(""""(?:premium|embedru|superembed|vidsrc)"\s*:\s*"([^"]+)"""")
                val serverUrls = serverRegex.findAll(decodedString).map { it.groupValues[1] }.toList()
                
                for (serverUrl in serverUrls) {
                    val fixedUrl = fixUrl(if (serverUrl.startsWith("//")) "https:$serverUrl" else serverUrl)
                    // Passes the decoded server links directly to Cloudstream's universal extractor!
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                // Fallback in case of decoding errors
            }
        }

        return true
    }
}
