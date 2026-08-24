package com.coxju

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Hqporner : MainAPI() {
    override var mainUrl              = "https://hqporner.com"
    override var name                 = "HQporner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/top"                   to "All time best porn",
        "${mainUrl}/top/month"             to "Month top porn",
        "${mainUrl}/top/week"              to "Week top porn",
        "${mainUrl}/category/1080p-porn"   to "1080p porn",
        "${mainUrl}/category/4k-porn"      to "4k porn",
        "${mainUrl}/category/60fps-porn"   to "60fps",
        "${mainUrl}/category/amateur"      to "Amateur",
        "${mainUrl}/category/anal-sex-hd"  to "Anal",
        "${mainUrl}/category/asian"        to "Asian",
        "${mainUrl}/category/babe"         to "Babe",
        "${mainUrl}/category/bdsm"         to "Bdsm",
        "${mainUrl}/category/beach-porn"   to "Beach",
        "${mainUrl}/category/big-ass"      to "Big Ass",
        "${mainUrl}/category/big-dick"     to "Big dick",
        "${mainUrl}/category/big-tits"     to "Big Tits",
        "${mainUrl}/category/blonde"       to "Blonde",
        "${mainUrl}/category/blowjob"      to "Blowjob",
        "${mainUrl}/category/bondage"      to "Bondage",
        "${mainUrl}/category/brunette"     to "Brunette",
        "${mainUrl}/category/casting"      to "Casting",
        "${mainUrl}/category/creampie"     to "Creampie",
        "${mainUrl}/category/cumshot"      to "Cumshot",
        "${mainUrl}/category/ebony"        to "Ebony",
        "${mainUrl}/category/gangbang"     to "GangBang",
        "${mainUrl}/category/handjob"      to "HandJob",
        "${mainUrl}/category/japanese-girls-porn"  to "Japanese",
        "${mainUrl}/category/lesbian"      to "Lesbian",
        "${mainUrl}/category/mature"       to "Mature",
        "${mainUrl}/category/milf"         to "Milf",
        "${mainUrl}/category/old-and-young" to "Old and Young",
        "${mainUrl}/category/outdoor"      to "Outdoor",
        "${mainUrl}/category/pov"          to "Pov",
        "${mainUrl}/category/public"       to "Public",
        "${mainUrl}/category/redhead"      to "Redhead",
        "${mainUrl}/category/russian"      to "Russian",
        "${mainUrl}/category/shaved-pussy" to "Shaved Pussy",
        "${mainUrl}/category/small-tits"   to "Small Tits",
        "${mainUrl}/category/stockings"    to "Stockings",
        "${mainUrl}/category/tattooed"     to "Tattooed",
        "${mainUrl}/category/teen-porn"    to "Teen porn",
        "${mainUrl}/category/uniforms"     to "Uniforms",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/$page"
        val document = app.get(url, referer = "$mainUrl/").document
        
        val home = document.select("a.image.featured, a.image, section.box.feature, .box, .item").mapNotNull { it.toSearchResult() }

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
        val linkElement = if (this.tagName() == "a") this else this.selectFirst("a")
        if (linkElement == null) return null
        
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
        val title = this.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() } 
            ?: linkElement.attr("title").takeIf { it.isNotBlank() } 
            ?: linkElement.text().trim()
            
        if (title.isEmpty()) return null
        
        val src = this.selectFirst("img")?.attr("src")
        val posterUrl = fixUrlNull(if (src?.startsWith("//") == true) "https:$src" else src)

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val safeQuery = query.replace(" ", "+")

        for (i in 1..5) {
            val url = "${mainUrl}/?q=${safeQuery}&p=$i"
            
            try {
                val document = app.get(url, referer = "$mainUrl/").document
                val results = document.select("a.image.featured, a.image, section.box.feature").mapNotNull { it.toSearchResult() }

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

        val title = document.selectFirst("h1")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() 
            ?: "Video"
            
        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        
        val rawPoster = document.selectFirst("video#flvv")?.attr("poster") ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = fixUrlNull(if (rawPoster?.startsWith("//") == true) "https:$rawPoster" else rawPoster)

        val tags = document.select("section h3 + p a, a[href*='/category/']").map { it.text().trim() }.distinct()
        
        val duration = document.selectFirst("li.icon.fa-clock-o")?.text()?.let { text ->
            val parts = text.split(" ")
            var totalMinutes = 0
            parts.forEach { part ->
                when {
                    part.endsWith("h") -> totalMinutes += part.removeSuffix("h").toIntOrNull()?.times(60) ?: 0
                    part.endsWith("m") -> totalMinutes += part.removeSuffix("m").toIntOrNull() ?: 0
                }
            }
            totalMinutes
        }

        val actorsList = document.select("li.icon.fa-star-o a, a[href*='/pornstar/']").mapNotNull { elem ->
            val name = elem.text().trim()
            if (name.isNotBlank()) ActorData(Actor(name)) else null
        }

        val recommendations = document.select("a.image.featured, section.box, .video").mapNotNull { elem ->
            val link = if (elem.tagName() == "a") elem else elem.selectFirst("a")
            if (link == null) return@mapNotNull null
            
            val recHref = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
            if (recHref == url) return@mapNotNull null

            val recTitle = elem.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() } ?: link.text().trim()
            val recSrc = elem.selectFirst("img")?.attr("src")
            val recPoster = fixUrlNull(if (recSrc?.startsWith("//") == true) "https:$recSrc" else recSrc)
            
            newMovieSearchResponse(recTitle, recHref, TvType.NSFW) {
                this.posterUrl = recPoster
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
            this.plot = description
            this.tags = tags
            this.duration = duration
            this.actors = actorsList
            this.recommendations = recommendations
        }
    }

    private fun getQuality(text: String): Int {
        return when {
            text.contains("2160") || text.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            text.contains("1080") -> Qualities.P1080.value
            text.contains("720") -> Qualities.P720.value
            text.contains("480") -> Qualities.P480.value
            text.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        var foundLinks = false
        val sourceName = this.name
        
        // This helper function scrapes the sources, and has a JS fallback built right in!
        fun processSources(doc: org.jsoup.nodes.Document, referer: String) {
            val sources = doc.select("source")
            for (source in sources) {
                val src = source.attr("src")
                val label = source.attr("title").takeIf { it.isNotBlank() } ?: source.attr("label") ?: "Video"
                
                if (src.contains(".mp4") || src.contains(".m3u8")) {
                    foundLinks = true
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = "$sourceName $label".trim(),
                            url = fixUrl(if (src.startsWith("//")) "https:$src" else src),
                            type = INFER_TYPE,
                            headers = mapOf("Referer" to referer),
                            quality = getQuality(label)
                        )
                    )
                }
            }
            
            // If they hid the video in javascript, this Regex rips it out
            if (!foundLinks) {
                val html = doc.html()
                val mp4Regex = Regex("""["']([^"']+\.mp4)["']""")
                mp4Regex.findAll(html).forEach { match ->
                    val src = match.groupValues[1]
                    foundLinks = true
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName,
                            name = sourceName,
                            url = fixUrl(if (src.startsWith("//")) "https:$src" else src),
                            type = INFER_TYPE,
                            headers = mapOf("Referer" to referer),
                            quality = getQuality(src)
                        )
                    )
                }
            }
        }
        
        // 1. Try checking the main page directly
        processSources(document, mainUrl)
        
        // 2. If it's not there, hunt for the real video iframe and skip the ads
        if (!foundLinks) {
            val iframes = document.select("iframe").mapNotNull { it.attr("src") }
            for (iframeSrc in iframes) {
                // FIXED: Specifically ignores the hf.html ad banner you found!
                if (iframeSrc.contains("hf.html") || iframeSrc.contains("ad.") || iframeSrc.contains("realsrv")) continue
                
                val iframeUrl = fixUrl(if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc)
                
                try {
                    val innerDoc = app.get(iframeUrl, referer = data).document
                    processSources(innerDoc, iframeUrl)
                    
                    if (!foundLinks) {
                        loadExtractor(iframeUrl, data, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    // Ignore dead iframes
                }
            }
        }

        return true
    }
}
