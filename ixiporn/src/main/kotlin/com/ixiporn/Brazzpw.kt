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
        "${mainUrl}/page/" to "Latest Updates"
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
        val document = app.get(data).document
        
        var iframeUrl = document.selectFirst("meta[itemprop='embedURL']")?.attr("content") 
            ?: document.selectFirst("iframe")?.attr("src")

        if (!iframeUrl.isNullOrEmpty()) {
            iframeUrl = fixUrl(iframeUrl).replace("&amp;", "&")
            val playerHtml = app.get(iframeUrl, headers = mapOf("Referer" to data)).text
            
            var htmlToSearch = playerHtml
            
            // STEP 1: A massive Regex upgrade to swallow the entire encrypted payload!
            val encryptedMatch = Regex("""var\s+d\s*=\s*'(.*?)'\.split\(""\)""").find(playerHtml)?.groupValues?.get(1)
            
            if (!encryptedMatch.isNullOrEmpty()) {
                // STEP 2: We must clean up JavaScript escape characters so our math equation stays synced!
                val cleanEncrypted = encryptedMatch
                    .replace("\\'", "'")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")

                // STEP 3: Decrypt the payload
                val crackedJson = decodeKVS(cleanEncrypted)
                
                // Add the naked links to our search area
                htmlToSearch += crackedJson 
            }
            
            // STEP 4: Grab the real link (now featuring its hash & time tokens!)
            var videoUrl = Regex("""(https?://[^"'\s\\]+?\.(?:m3u8|mp4)[^"'\s\\]*)""").find(htmlToSearch)?.groupValues?.get(1)
            
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
                // Failsafe backup
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
