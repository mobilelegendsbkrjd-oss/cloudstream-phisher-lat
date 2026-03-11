package com.dramafun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaFunPlugin : Plugin() {
    override fun load() {
        registerMainAPI(DramaFun())
    }
}
