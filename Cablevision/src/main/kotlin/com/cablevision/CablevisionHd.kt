package com.cablevision

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.models.cablevisionhd.toTvShows
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.tv.streamflix.cloudstream.newHomePageResponse
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

    override suspend fun getHome(): HomePageResponse = coroutineScope {
        try {
            val document = service.getPage(baseUrl)
            val allShows = document.toTvShows()

            val deportes = allShows.filter {
                it.title.contains("sport", true) || it.title.contains("espn", true) || it.title.contains("fox", true)
            }
            val noticias = allShows.filter {
                it.title.contains("news", true) || it.title.contains("cnn", true) || it.title.contains("noticias", true)
            }
            val entretenimiento = allShows.filter {
                it.title.contains("hbo", true) || it.title.contains("max", true) ||
                        it.title.contains("cine", true) || it.title.contains("star", true)
            }

            // Usar el constructor actualizado
            newHomePageResponse(
                listOf(
                    HomePageList("Todos", allShows),
                    HomePageList("Deportes", deportes),
                    HomePageList("Noticias", noticias),
                    HomePageList("Entretenimiento", entretenimiento)
                ),
                hasNext = false
            )

        } catch (e: Exception) {
            Log.e("CableVisionHD", "Error getHome: ${e.message}")
            newHomePageResponse(emptyList(), hasNext = false)
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        return try {
            val fullUrl = if (id.startsWith("http")) id else "$baseUrl/$id"
            val document = service.getPage(fullUrl)

            val title = document.selectFirst("h1")?.text() ?: "Canal en Vivo"
            val poster = document.selectFirst("div.card-body img")?.attr("src")?.let {
                if (!it.startsWith("http")) "$baseUrl/$it" else it
            }

            val season = Season(
                id = id,
                number = 1,
                title = "En Vivo",
                episodes = listOf(Episode(id = id, number = 1, title = "Señal en Directo", poster = poster))
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
            Log.e("CableVisionHD", "Error getTvShow: ${e.message}")
            TvShow(id = id, title = "Error de carga")
        }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val fullUrl = if (id.startsWith("http")) id else "$baseUrl/$id"
            val document = service.getPage(fullUrl)

            val servers = mutableListOf<Video.Server>()
            document.select("a.btn[target=iframe]").forEach {
                servers.add(Video.Server(it.attr("href"), it.text().ifEmpty { "Opción Principal" }))
            }

            if (servers.isEmpty() && document.select("iframe").isNotEmpty())
                servers.add(Video.Server(fullUrl, "Directo"))

            servers
        } catch (e: Exception) {
            Log.e("CableVisionHD", "Error getServers: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        var currentUrl = server.id
        var currentReferer = baseUrl

        repeat(4) { depth ->
            try {
                val document = service.getPage(currentUrl, currentReferer)
                val html = document.html()

                // 1️⃣ M3U8 directo
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(html)?.let {
                    val url = it.groupValues[1].replace("\\/", "/")
                    return Video(source = url, headers = mapOf("Referer" to currentUrl, "User-Agent" to USER_AGENT))
                }

                // 2️⃣ Script Packer
                Regex("""eval\(function\(p,a,c,k,e,d\).*?\)""").findAll(html).forEach { match ->
                    JsUnpacker(match.value).unpack()?.let { unpacked ->
                        Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.let { m ->
                            val url = m.groupValues[1].replace("\\/", "/")
                            return Video(source = url, headers = mapOf("Referer" to currentUrl, "User-Agent" to USER_AGENT))
                        }
                    }
                }

                // 3️⃣ Triple Base64 clásico
                if (html.contains("const decodedURL")) {
                    document.select("script").forEach {
                        if (it.data().contains("const decodedURL")) {
                            val encodedUrl = it.data().substringAfter("atob(\"").substringBefore("\"))))")
                            try {
                                val decoded = String(
                                    Base64.decode(
                                        String(
                                            Base64.decode(
                                                String(Base64.decode(encodedUrl, Base64.DEFAULT)),
                                                Base64.DEFAULT
                                            )
                                        ), Base64.DEFAULT
                                    )
                                )
                                return Video(source = decoded, headers = mapOf("Referer" to currentUrl, "User-Agent" to USER_AGENT))
                            } catch (_: Exception) {}
                        }
                    }
                }

                // 4️⃣ Patrones genéricos
                listOf(
                    Regex("""source\s*:\s*["']([^"']+)["']"""),
                    Regex("""file\s*:\s*["']([^"']+)["']"""),
                    Regex("""var\s+src\s*=\s*["']([^"']+)["']"""),
                    Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
                ).forEach { r ->
                    r.find(html)?.let {
                        val url = it.groupValues[1].replace("\\/", "/")
                        if (url.startsWith("http")) return Video(source = url, headers = mapOf("Referer" to currentUrl, "User-Agent" to USER_AGENT))
                    }
                }

                // 5️⃣ Profundizar en iframe
                val iframeSrc = document.select("iframe").attr("src")
                if (iframeSrc.isNotEmpty()) {
                    val nextUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$baseUrl/$iframeSrc"
                    if (nextUrl == currentUrl) return Video(source = "", subtitles = emptyList())
                    currentReferer = currentUrl
                    currentUrl = nextUrl
                } else return Video(source = "", subtitles = emptyList())

            } catch (_: Exception) {
                return Video(source = "", subtitles = emptyList())
            }
        }

        return Video(source = "", subtitles = emptyList())
    }

    // Resto de overrides vacíos
    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getTvShows(page: Int): List<TvShow> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw NotImplementedError()
    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> = emptyList()
    override suspend fun getGenre(id: String, page: Int): Genre = throw NotImplementedError()
    override suspend fun getPeople(id: String, page: Int): People = throw NotImplementedError()
}
