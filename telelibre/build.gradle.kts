import com.lagradost.cloudstream3.gradle.CloudstreamExtension

// Aplicamos el plugin correctamente
apply(plugin = "com.lagradost.cloudstream3.gradle")

configure<CloudstreamExtension> {
    // Usamos asignación directa o métodos según tu versión del plugin
    name = "Tele-Libre"
    description = "Extensión para ver televisión en vivo de Argentina y el mundo"
    language = "es"
    authors = listOf("bkrjd")
    
    // Status 1: Producción/Funcional
    status = 1
    
    // Lista de tipos de contenido
    tvTypes = listOf("LiveStream")
    
    // URL del icono (basado en el mapa técnico)
    iconUrl = "https://tele-libre.fans/img/logo.png"
}

dependencies {
    // Implementación estándar de Kotlin y Jsoup para el scrapeo
    implementation(kotlin("stdlib"))
    implementation("org.jsoup:jsoup:1.15.3")
}
