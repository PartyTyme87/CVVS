package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Brazzpw : MainAPI() {
    override var mainUrl              = "https://brazzpw.xyz"
    override var name                 = "Brazzpw"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/page/" to "Latest Updates",
        "${mainUrl}/pornstars/gender/female/free-brazz-premium-full-new-2026/" to "Models", // ADDED: Your Models Shelf!
        "${mainUrl}/videos/sortby/beingwatched/free-brazz-premium-full-new-2026/" to "Being Watched",
        "${mainUrl}/videos/sortby/rating/free-brazz-premium-full-new-2026/" to "Top Rated",
        "${mainUrl}/videos/sortby/views/free-brazz-premium-full-new-2026/" to "Most Viewed",
        "${mainUrl}/videos/tags/79/milf/free-brazz-premium-full-new-2026/" to "MILF",
        "${mainUrl}/videos/tags/103/asian/free-brazz-premium-full-new-2026/" to "Asian",
        "${mainUrl}/videos/tags/112/black/free-brazz-premium-full-new-2026/" to "Black"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            if (request.data.endsWith("/page/")) request.data.removeSuffix("page/") else request.data
        } else {
            if (request.data.contains("free-brazz-premium-full-new-2026/")) {
                request.data.replace("free-brazz-premium-full-new-2026/", "page/$page/free-brazz-premium-full-new-2026/")
            } else {
                request.data + "$page/"
            }
        }
        
        val document = app.get(url).document
        
        // Check if the current shelf is the Models shelf
        val isModelShelf = request.name == "Models" || request.data.contains("/pornstars/")
        
        // Grab the items (broadened slightly just in case models use a different class)
        val home = document.select("article.loop-video, article.thumb-block, article").mapNotNull { 
            it.toSearchResult(isModelShelf) 
        }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    // UPDATED: Incorporating your TvSeries snippet!
    private fun Element.toSearchResult(isModel: Boolean = false): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        // Backup title checks in case the title attribute is empty on model cards
        val title = linkElement.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: linkElement.text().trim()
            
        if (title.isEmpty()) return null
        
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() } 
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        // If it's a model, tell Cloudstream it's a TV Series (folder)!
        return if (isModel || href.contains("/pornstar") || href.contains("/model")) {
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
            val url = if (i == 1) "${mainUrl}/?s=$safeQuery" else "${mainUrl}/page/$i/?s=$safeQuery"
            val document = app.get(url).document
            val results = document.select("article.loop-video, article.thumb-block").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) searchResponse.addAll(results) else break
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?.replace("BrazzPW.XYZ -", "")?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()

        // UPDATED: If the user clicked a Model, scrape all the videos on their page!
        if (url.contains("/pornstar") || url.contains("/model")) {
            val episodes = document.select("article.loop-video, article.thumb-block").mapNotNull { elem ->
                val link = elem.selectFirst("a") ?: return@mapNotNull null
                val epHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                
                val epTitle = link.attr("title").takeIf { it.isNotBlank() } 
                    ?: elem.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: "Video"
                    
                val epImg = elem.selectFirst("img")
                val epPoster = fixUrlNull(
                    epImg?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: epImg?.attr("src")?.takeIf { it.isNotBlank() }
                )
                
                newEpisode(epHref) {
                    this.name = epTitle
                    this.posterUrl = epPoster
                }
            }
            
            // Return a Series response featuring the episodes we just scraped
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // Standard Movie response for regular video links
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    private fun unescapeJS(input: String): String {
        val sb = java.lang.StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                val next = input[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun decodeKVS(encoded: String): String {
        val builder = java.lang.StringBuilder()
        for (i in encoded.indices) {
            val c = encoded[i]
            val n = c.code - 32
            if (n in 0..94) {
                builder.append((32 + (n + i) % 95).toChar())
            } else {
                builder.append(c)
            }
        }
        return builder.toString()
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val videoId = Regex("""/video/(\d+)""").find(data)?.groupValues?.get(1)
        val document = app.get(data).document
        
        var iframeUrl = document.selectFirst("meta[itemprop='embedURL']")?.attr("content") 
            ?: document.selectFirst("iframe")?.attr("src")

        if (!iframeUrl.isNullOrEmpty()) {
            iframeUrl = fixUrl(iframeUrl).replace("&amp;", "&")
            val playerHtml = app.get(iframeUrl, headers = mapOf("Referer" to data)).text
            
            var videoUrl: String? = null
            
            val encryptedMatch = Regex("""var\s+[a-zA-Z0-9_]+\s*=\s*'(.*?)'\.split\(""\)""").find(playerHtml)?.groupValues?.get(1)
            
            if (!encryptedMatch.isNullOrEmpty()) {
                val cleanEncrypted = unescapeJS(encryptedMatch)
                val crackedJson = decodeKVS(cleanEncrypted)
                videoUrl = Regex("""(https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""").find(crackedJson)?.groupValues?.get(1)
            }
            
            if (videoUrl.isNullOrEmpty() && videoId != null) {
                val token = (System.currentTimeMillis() / 10_000_000).toString()
                videoUrl = "${mainUrl}/player/m3u8_$videoId.m3u8?hash=$token&time=$token"
            }

            if (!videoUrl.isNullOrEmpty()) {
                val cleanUrl = videoUrl.replace("&amp;", "&").replace("\\", "")
                
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = cleanUrl,
                        type = INFER_TYPE
                    ) {
                        this.referer = iframeUrl
                        this.headers = mapOf(
                            "Referer" to iframeUrl,
                            "Origin" to mainUrl,
                            "Accept" to "*/*"
                        )
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
