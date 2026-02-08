import com.lagradost.cloudstream3.gradle.CloudstreamExtension

// Aplicar el plugin de Cloudstream (asegúrate de tenerlo definido en tu proyecto raíz)
apply(plugin = "com.lagradost.cloudstream3.gradle")

version = 1 // Puedes incrementar esto cada vez que actualices la extensión

configure<CloudstreamExtension> {
    [span_1](start_span)// Datos basados en el origen: https://tele-libre.fans/[span_1](end_span)
    name = "Tele-Libre"
    description = "Extensión para ver televisión en vivo de Argentina y el mundo a través de Tele-Libre"
    language = "es"
    authors = listOf("TuNombreOAlias") 
    
    // Status 1 significa que la extensión es funcional (Production)
    status = 1 
    
    [span_2](start_span)// Cambiamos a LiveStream porque el sitio ofrece TV en vivo[span_2](end_span)
    tvTypes = listOf("LiveStream") 
    
    [span_3](start_span)// Usamos el logo principal detectado en el mapeo técnico[span_3](end_span)
    iconUrl = "https://tele-libre.fans/img/logo.png" 
}

dependencies {
    // Estas son las dependencias básicas para que compile
    implementation(kotlin("stdlib"))
    [span_4](start_span)// Jsoup es fundamental para procesar los selectores img.w-28.h-28 detectados[span_4](end_span)
    implementation("org.jsoup:jsoup:1.15.3") 
}
