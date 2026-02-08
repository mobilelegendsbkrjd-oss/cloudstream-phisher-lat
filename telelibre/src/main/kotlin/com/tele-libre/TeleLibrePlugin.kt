package com.telelibre

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TeleLibrePlugin: Plugin() {
    override fun load(context: Context) {
        // Aquí registramos nuestra API principal
        registerMainAPI(TeleLibre())
    }
}
