package com.ukdevilz

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.preference.PreferenceManager

@CloudstreamPlugin
class UKDevilzPlugin : Plugin() {
    override fun load(context: Context) {
        // Grab the Android preferences directly from the app context
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Pass the preferences directly into our scraper class
        registerMainAPI(UKDevilz(prefs))
        
        // Attach the settings menu to the gear icon in the app
        this.settings = UKDevilzSettings::class.java
    }
}
