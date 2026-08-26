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

    override val mainPage = mainPageOf(
        "${mainUrl}/all/?sort=created_at" to "All Films",
        "${mainUrl}/popular/?filter=all&sort=by_likes" to "Popular",
        "${mainUrl}/category/pov/?sort=created_at" to "POV",
        "${mainUrl}/category/big-tits/?sort=created_at" to "Big Tits",
        "${mainUrl}/category/big-butts/?sort=created_at" to "Big Ass",
        "${mainUrl}/category/anal-sex/?sort=created_at" to "Anal Sex",
        "${mainUrl}/category/lesbians/?sort=created_at" to "Lesbians",
        "${mainUrl}/studio/89/?sort=created_at" to "Brazzers",
        "${mainUrl}/studio/696/?sort=created_at" to "Blacked",
        "${mainUrl}/studio/607/?sort=created_at" to "Tushy",
        "${mainUrl}/studio/16/?sort=created_at" to "Evil Angel"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        }
        
        val document = app.get(url, referer = "$mainUrl/").document
        
        val home = document.select(".item.list-film-item").mapNotNull { it.toSearchResult() }

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
        
        if (href.contains("/category/") || href.contains("/studio/")) return null
        
        val title = this.selectFirst("span[itemprop='name']")?.text()?.trim() 
            ?: this.selectFirst("div[itemprop='name'] span")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
            
        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..5) {
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

        val recommendations = document.select("div#home .item.list-film-item").mapNotNull { it.toSearchResult() }

        val scriptData = document.select("script:containsData(var videoList)").firstOrNull()?.data()
        val videoParts = mutableListOf<String>()
        
        if (scriptData != null) {
            val srcRegex = Regex(""""src":"([^"]+)"""")
            srcRegex.findAll(scriptData).forEach { match ->
                val mp4Link = match.groupValues[1].replace("\\/", "/")
                if (!videoParts.contains(mp4Link)) {
                    videoParts.add(mp4Link)
                }
            }
        }

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
        // FIXED: Strictly uses the new ExtractorLink parameters!
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
