package com.ennovelas

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ennovelasPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EnNovelas())
    }
}