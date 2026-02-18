package com.cablevision

import com.lagradost.cloudstream3.plugins.*

class CablevisionHdPlugin : Plugin() {
    override fun load() {
        registerMainAPI(CablevisionHd())
    }
}
