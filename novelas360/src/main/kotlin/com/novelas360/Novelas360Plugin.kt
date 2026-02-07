package com.novelas360

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Novelas360Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Novelas360())
    }
}
