package com.pornez

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PornezPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Pornez())
    }
}
