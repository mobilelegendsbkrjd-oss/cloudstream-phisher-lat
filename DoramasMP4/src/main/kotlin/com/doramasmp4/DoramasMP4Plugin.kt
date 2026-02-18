package com.doramasmp4

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DoramasMP4Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DoramasMP4())
    }
}
