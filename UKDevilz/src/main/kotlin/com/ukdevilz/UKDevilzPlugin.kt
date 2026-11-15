package com.ukdevilz

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class UKDevilzPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the scraper
        registerMainAPI(UKDevilz())
        
        // Attach the settings menu to the gear icon in the app
        this.settings = UKDevilzSettings::class.java
    }
}
