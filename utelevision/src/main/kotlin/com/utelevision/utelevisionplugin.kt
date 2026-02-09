package com.utelevision

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class utelevisionplugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(utelevision())
    }
}
