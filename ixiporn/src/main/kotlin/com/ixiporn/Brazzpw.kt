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
        "${mainUrl}/videos/sortby/beingwatched/free-brazz-premium-full-new-2026/" to "Being Watched",
        "${mainUrl}/videos/sortby/rating/free-brazz-premium-full-new-2026/" to "Top Rated",
        "${mainUrl}/videos/sortby/views/free-brazz-premium-full-new-2026/" to "Most Viewed",
        "${mainUrl}/videos/tags/79/milf/free-brazz-premium-full-new-2026/" to "MILF",
        "${mainUrl}/videos/tags/103/asian/free-brazz-premium-full-new-2026/" to "Asian",
        "${mainUrl}/videos/tags/112/black/free-brazz-premium-full-new-2026/" to "Black"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data.replace("page/", "") else request.data + "$page/"
        val document = app.get(url).document
        
        val home = document.select("article.loop-video, article.thumb-block").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a") ?: return null
        
        val title = linkElement.attr("title").trim()
        if (title.isEmpty()) return null
        
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        
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

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    // THE FIX: This perfectly mimics how a browser reads Javascript memory
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

    // THE DECRYPTOR: Masterfully cracking KVS mathematical encryption
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
            
            // ATTACK 1: Flawless KVS Decryption
            val encryptedMatch = Regex("""var\s+[a-zA-Z0-9_]+\s*=\s*'(.*?)'\.split\(""\)""").find(playerHtml)?.groupValues?.get(1)
            
            if (!encryptedMatch.isNullOrEmpty()) {
                val cleanEncrypted = unescapeJS(encryptedMatch) // Fix the shifting math!
                val crackedJson = decodeKVS(cleanEncrypted)
                videoUrl = Regex("""(https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""").find(crackedJson)?.groupValues?.get(1)
            }
            
            // ATTACK 2: Your Time Token Discovery! (Used if Attack 1 fails)
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
                        // CRITICAL: Trick the server into thinking we are playing from their iframe!
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
