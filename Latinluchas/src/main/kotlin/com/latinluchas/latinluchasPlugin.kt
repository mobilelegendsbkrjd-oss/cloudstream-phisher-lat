// Latinluchas/src/main/kotlin/com/latinluchas/LatinLuchasPlugin.kt

package com.latinluchas

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LatinLuchasPlugin : Plugin() {
    override fun load() {
        // Registro automático en versiones recientes (no necesitas retornar lista)
        registerMainAPI(LatinLuchas())
    }
}
