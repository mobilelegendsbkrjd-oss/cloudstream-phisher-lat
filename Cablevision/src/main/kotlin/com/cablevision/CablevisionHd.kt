package com.cablevision

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.JsUnpacker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.*
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object CablevisionHd : Provider {

    override val name = "CableVisionHD"
    override val baseUrl = "https://www.cablevisionhd.com"
    override val language = "es"
    override val logo = "https://i.ibb.co/4gMQkN2b/imagen-2025-09-05-212536248.png"

    private const val TAG = "CablevisionHd"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cookieJar(MyCookieJar())
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .build()
            chain.proceed(request)
        }.build()

    private val service = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(JsoupConverterFactory.create())
        .client(client)
        .build()
        .create(Service::class.java)

    interface Service {
        @GET
        suspend fun getPage(@Url url: String, @Header("Referer") referer: String = baseUrl): Document
    }

    override suspend fun getMainPage(): HomePageResponse = coroutineScope {
        try {
            val document = service.getPage(baseUrl)
            val allShows = document.toTvShows()

            val categories = listOf(
                HomePageList("Todos los Canales", allShows),
                HomePageList(
                    "Deportes", allShows.filter {
                        it.title.contains("sport", true) ||
                                it.title.contains("espn", true) ||
                                it.title.contains("fox", true)
                    }
                ),
                HomePageList(
                    "Noticias", allShows.filter {
                        it.title.contains("news", true) ||
                                it.title.contains("noticias", true) ||
                                it.title.contains("cnn", true)
                    }
                ),
                HomePageList(
                    "Cine y Series", allShows.filter {
                        it.title.contains("hbo", true) ||
                                it.title.contains("max", true) ||
                                it.title.contains("cine", true) ||
                                it.title.contains("warner", true) ||
                                it.title.contains("star", true)
                    }
                ),
                HomePageList(
                    "Información", listOf(
                        TvShow(
                            id = "creador-info",
                            title = "Reportar problemas",
                            poster = "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg"
                        ),
                        TvShow(
                            id = "apoyo-info",
                            title = "Apoya al Proveedor",
                            poster = "https://i.ibb.co/B234HsZg/APOYO-NANDO.png"
                        )
                    )
                )
            )

            // 👈 AQUÍ EL FIX: se usa newHomePageResponse
            newHomePageResponse(categories)

        } catch (e: Exception) {
            Log.e(TAG, "Error en getMainPage: ${e.message}")
            newHomePageResponse(emptyList())
        }
    }

    override suspend fun search(query: String, page: Int): List<Show> {
        return try {
            val document = service.getPage(baseUrl)
            val allShows = document.toTvShows()
            allShows.filter { it.title.contains(query, true) }
        } catch (e: Exception) {
            Log.e(TAG, "Error en search: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        if (id == "creador-info" || id == "apoyo-info") return getInfoItem(id)
        return try {
            val fullUrl = if (id.startsWith("http")) id else "$baseUrl/$id"
            val document = service.getPage(fullUrl)

            val title = document.selectFirst("div.card-body h2")?.text()
                ?: document.selectFirst("h1")?.text()
                ?: "Canal en Vivo"

            val poster = document.selectFirst("div.card-body img")?.attr("src")?.let {
                if (!it.startsWith("http")) "$baseUrl/$it" else it
            }

            val season = Season(
                id = id, number = 1, title = "En Vivo", episodes = listOf(
                    Episode(id = id, number = 1, title = "Señal en Directo", poster = poster)
                )
            )

            TvShow(
                id = id,
                title = title,
                poster = poster,
                banner = poster,
                overview = "Transmisión en directo",
                seasons = listOf(season)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error en getTvShow: ${e.message}")
            TvShow(id = id, title = "Error de carga")
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val fullUrl = if (id.startsWith("http")) id else "$baseUrl/$id"
            val document = service.getPage(fullUrl)
            val servers = mutableListOf<Video.Server>()

            // Botones que apuntan a iframe
            document.select("a.btn.btn-md[target=iframe]").forEach {
                servers.add(
                    Video.Server(
                        id = it.attr("href"),
                        name = it.text().ifEmpty { "Opción Principal" }
                    )
                )
            }

            // fallback: iframe directo
            if (servers.isEmpty() && document.select("iframe").isNotEmpty()) {
                servers.add(Video.Server(id = fullUrl, name = "Directo"))
            }
            servers
        } catch (e: Exception) {
            Log.e(TAG, "Error en getServers: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        var currentUrl = server.id
        var currentReferer = baseUrl
        repeat(4) { depth ->
            try {
                val document = service.getPage(currentUrl, referer = currentReferer)
                val html = document.html()

                // 1️⃣ M3U8 directo
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.let {
                    val url = it.groupValues[1].replace("\\/", "/")
                    return Video(source = url, headers = mapOf("Referer" to currentUrl, "User-Agent" to USER_AGENT))
                }

                // 2️⃣ Script Packer
                Regex("""eval\(function\(p,a,c,k,e,d\).*?\)""").findAll(html).forEach { match ->
                    JsUnpacker(match.value).unpack()?.let { unpacked ->
                        Regex("""source\s*:\s*["']([^"']+)["']""")
                            .find(unpacked)?.groupValues?.get(1)?.let { url ->
                                return Video(
                                    source = url.replace("\\/", "/"),
                                    headers = mapOf("Referer" to currentUrl, "User-Agent" to USER_AGENT)
                                )
                            }
                    }
                }

                // 3️⃣ Triple Base64 clásico Cablevision
                document.select("script").forEach {
                    if (it.data().contains("const decodedURL")) {
                        val encoded = it.data().substringAfter("atob(\"").substringBefore("\"))))")
                        try {
                            val decoded = String(
                                Base64.decode(
                                    String(
                                        Base64.decode(
                                            String(Base64.decode(encoded, Base64.DEFAULT)),
                                            Base64.DEFAULT
                                        )
                                    ), Base64.DEFAULT
                                )
                            )
                            return Video(source = decoded, headers = mapOf("Referer" to currentUrl, "User-Agent" to USER_AGENT))
                        } catch (_: Exception) {}
                    }
                }

                // 4️⃣ Iframe para profundizar
                val iframeSrc = document.select("iframe").attr("src")
                if (iframeSrc.isNotEmpty() && iframeSrc != currentUrl) {
                    currentReferer = currentUrl
                    currentUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$baseUrl/$iframeSrc"
                    return@getVideo null
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error nivel $depth: ${e.message}")
            }
        }
        return Video(source = "", subtitles = emptyList())
    }

    private fun getInfoItem(id: String) = TvShow(
        id = id,
        title = if (id == "creador-info") "Reportar problemas" else "Apoya al Proveedor",
        poster = if (id == "creador-info")
            "https://i.ibb.co/dsknGBHT/Imagen-de-Whats-App-2025-09-06-a-las-19-00-50-e8e5bcaa.jpg"
        else "https://i.ibb.co/B234HsZg/APOYO-NANDO.png",
        banner = poster,
        overview = if (id == "creador-info") "Reporta errores a @NandoGT o @Nandofs." else "Escanea el QR para apoyar el proyecto.",
        seasons = emptyList()
    )
}
