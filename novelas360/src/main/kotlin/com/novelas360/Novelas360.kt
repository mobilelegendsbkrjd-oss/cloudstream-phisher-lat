package com.novelas360

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Novelas360 : MainAPI() {

    override var mainUrl = "https://novelas360.cyou"
    override var name = "Novelas360"
    override val hasMainPage = false
    override var lang = "es"

    private val chromeUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val id = data.substringAfterLast("/")

        // ---------------------------
        // STEP 1 - GET embed_player
        // ---------------------------

        val embedResponse = app.get(
            "$mainUrl/player/embed_player.php?vid=$id&pop=0",
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to "$mainUrl/e/$id",
                "Origin" to mainUrl
            )
        )

        val embedHtml = embedResponse.text

        // Extraer SH correctamente
        val sh = Regex("""["']sh["']\s*[:=]\s*["']([a-f0-9]+)["']""")
            .find(embedHtml)
            ?.groupValues?.get(1)
            ?: return false

        // Obtener cookies (MUY IMPORTANTE)
        val cookies = embedResponse.cookies

        // ---------------------------
        // STEP 2 - POST get_md5.php
        // ---------------------------

        val postBody = """
        {
          "htoken":"",
          "sh":"$sh",
          "ver":"4",
          "secure":"0",
          "adb":"96958",
          "v":"$id",
          "token":"",
          "gt":"",
          "embed_from":"0",
          "wasmcheck":0,
          "adscore":"",
          "click_hash":"",
          "clickx":0,
          "clicky":0
        }
        """.trimIndent()

        val md5Response = app.post(
            "$mainUrl/player/get_md5.php",
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to "$mainUrl/e/$id",
                "Origin" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/json"
            ),
            data = postBody,
            cookies = cookies
        )

        val responseText = md5Response.text

        // ---------------------------
        // STEP 3 - Extraer M3U8 real
        // ---------------------------

        val m3u8 = Regex("""https?:\/\/[^"]+\.m3u8[^"]*""")
            .find(responseText)
            ?.value
            ?: return false

        // ---------------------------
        // STEP 4 - Enviar a player
        // ---------------------------

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.type = ExtractorLinkType.M3U8
                this.headers = mapOf(
                    "User-Agent" to chromeUA,
                    "Referer" to mainUrl
                )
            }
        )

        return true
    }
}
