package com.pandrama

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PandramaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pandrama())
    }
}
