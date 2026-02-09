package com.newpipe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class newpipe : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(newpipeProvider())
    }
}
