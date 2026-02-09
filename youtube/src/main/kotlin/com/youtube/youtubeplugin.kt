package com.youtube

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class youtubeplugin : BasePlugin() {
    override fun load() {
        registerMainAPI(youtubeprovider())
    }
}