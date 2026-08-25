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
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url, referer = "$mainUrl/").document
        
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

        val textContent = this.text()
        val isTvSeries = textContent.contains("SS ", ignoreCase = true) || 
                         textContent.contains("EP ", ignoreCase = true) || 
                         textContent.contains("Season", ignoreCase = true)

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
            ?: "Video"
            
        val description = document.selectFirst("div.description p")?.text()?.trim()
        val poster = fixUrlNull(document.selectFirst("img[itemprop='image']")?.attr("data-src"))

        val tags = document.select("div.tags a").map { it.text().trim() }
        val actorsList = document.select("div.casts a").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }

        val recommendations = document.select("div.related.items a.item").mapNotNull { elem ->
            val recHref = fixUrlNull(elem.attr("href")) ?: return@mapNotNull null
            if (recHref == url) return@mapNotNull null

            val recTitle = elem.selectFirst(".name")?.text()?.trim() ?: "Video"
            val recPoster = fixUrlNull(elem.selectFirst("img")?.attr("data-src"))
            
            newMovieSearchResponse(recTitle, recHref, TvType.Movie) {
                this.posterUrl = recPoster
            }
        }

        val episodesList = document.select("ul.episodes li a")
        val firstEpText = episodesList.firstOrNull()?.text()?.trim() ?: ""
        val isTvSeries = episodesList.size > 1 || (!firstEpText.contains("Movie", ignoreCase = true) && firstEpText.isNotBlank())

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            
            episodesList.forEach { epNode ->
                val epText = epNode.text().trim()
                val onclickAttr = epNode.attr("onclick")
                
                val onclickMatch = Regex("""infoEpisodio\(\d+,\s*(\d+),\s*(\d+)\)""").find(onclickAttr)
                
                val seasonNum = onclickMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = onclickMatch?.groupValues?.get(2)?.toIntOrNull() ?: Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                
                val epHref = "$url?season=$seasonNum&episode=$epNum"

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epText
                        this.season = seasonNum
                        this.episode = epNum
                    }
                )
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
        val cleanUrl = data.substringBefore("?")
        val document = app.get(cleanUrl, referer = "$mainUrl/").document
        var foundLinks = false
        
        val base64Script = document.selectFirst("script[id$=-js-extra]")?.attr("src")
        
        if (base64Script != null && base64Script.contains("base64,")) {
            val encodedData = base64Script.substringAfter("base64,").trim()
            
            try {
                val decodedString = String(android.util.Base64.decode(encodedData, android.util.Base64.DEFAULT)).replace("\\/", "/")
                
                val imdbId = Regex(""""(?:imdb_id|tvimdbid)"\s*:\s*"([^"]+)"""").find(decodedString)?.groupValues?.get(1)
                
                if (imdbId != null && imdbId.startsWith("tt")) {
                    val seasonRegex = Regex("""season=(\d+)""").find(data)?.groupValues?.get(1)
                    val episodeRegex = Regex("""episode=(\d+)""").find(data)?.groupValues?.get(1)
                    
                    val vidsrcUrl = if (seasonRegex != null && episodeRegex != null) {
                        "https://vidsrc.me/embed/tv?imdb=$imdbId&season=$seasonRegex&episode=$episodeRegex"
                    } else {
                        "https://vidsrc.me/embed/movie?imdb=$imdbId"
                    }
                    
                    loadExtractor(vidsrcUrl, data, subtitleCallback, callback)
                    foundLinks = true
                }

                val serverRegex = Regex(""""(?:premium|embedru|superembed|vidsrc|server\d*)"\s*:\s*"([^"]+)"""")
                val serverUrls = serverRegex.findAll(decodedString).map { it.groupValues[1] }.toList()
                
                for (serverUrl in serverUrls) {
                    if (serverUrl.isNotBlank() && !serverUrl.contains("vidsrc.me")) {
                        val fixedUrl = fixUrl(if (serverUrl.startsWith("//")) "https:$serverUrl" else serverUrl)
                        loadExtractor(fixedUrl, data, subtitleCallback, callback)
                        
                        try {
                            val iframeHtml = app.get(fixedUrl, referer = mainUrl).text
                            val m3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                            m3u8Regex.findAll(iframeHtml).forEach { match ->
                                // FIXED: Uses the proper function arguments instead of the lambda builder
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name HD",
                                        url = match.groupValues[1],
                                        referer = fixedUrl,
                                        quality = Qualities.Unknown.value,
                                        isM3u8 = true
                                    )
                                )
                                foundLinks = true
                            }
                        } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!foundLinks) {
            val iframeSrc = document.selectFirst("iframe#iframe")?.let { 
                it.attr("data-src").takeIf { src -> src.isNotBlank() && src != "about:blank" } ?: it.attr("src") 
            }
            if (!iframeSrc.isNullOrBlank() && iframeSrc != "about:blank") {
                val fixedIframe = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
                loadExtractor(fixedIframe, data, subtitleCallback, callback)
                foundLinks = true
            }
        }

        return foundLinks
    }
}
