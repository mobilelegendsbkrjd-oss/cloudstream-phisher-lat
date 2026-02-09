package com.megadede

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class megadedeplugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(megadede())
    }
}
