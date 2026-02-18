package com.novelas360

import com.cloudstream.backend.ExtractorLink
import com.cloudstream.backend.ExtractorLinkType
import com.cloudstream.backend.MainAPI
import com.cloudstream.backend.models.SubtitleFile

class Novelas360 : MainAPI() {

    private val mainUrl = "https://novelas360.cyou"
    private val chromeUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: suspend (ExtractorLink) -> Unit
    ): Boolean {

        // Extraer ID del video
        val id = data.substringAfterLast("/")

        // Obtener embed page
        val embedResponse = app.get(
            "$mainUrl/player/embed_player.php?vid=$id&pop=0",
            headers = mapOf(
                "User-Agent" to chromeUA,
                "Referer" to "$mainUrl/e/$id",
                "Origin" to mainUrl
            )
        )

        // Extraer 'sh' del JS
        val sh = Regex("""["']sh["']\s*[:=]\s*["']([a-f0-9]+)["']""")
            .find(embedResponse.text)
            ?.groupValues?.get(1)
            ?: return false

        val cookies = embedResponse.cookies

        // Preparar payload JSON para get_md5
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

        // POST a get_md5.php
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

        // Buscar URL m3u8
        val m3u8 = Regex("""https?:\/\/[^"]+\.m3u8[^"]*""")
            .find(md5Response.text)
            ?.value
            ?: return false

        // Crear ExtractorLink usando la nueva firma
        newExtractorLink(
            source = name,                 // nombre del proveedor
            name = name,                   // nombre a mostrar
            url = m3u8,                    // link m3u8
            type = ExtractorLinkType.M3U8  // tipo M3U8
        ) { link ->
            callback(link)                 // entregamos el link al callback
        }

        return true
    }

    override fun name(): String = "Novelas360"

    override fun mainPage(): String = mainUrl
}
