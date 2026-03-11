package com.dramafun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DramaFunProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DramaFun())
        
    }
}
