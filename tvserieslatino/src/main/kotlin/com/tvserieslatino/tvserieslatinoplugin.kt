package com.tvserieslatino

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class TvSeriesLatinoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TvSeriesLatino())
    }
}