package com.ukdevilz

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UKDevilzPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the scraper
        registerMainAPI(UKDevilz())
        
        // Attach the settings menu to the gear icon using Android's FragmentManager
        this.openSettings = { ctx ->
            val fragment = UKDevilzSettings()
            val manager = (ctx as AppCompatActivity).supportFragmentManager
            manager.beginTransaction()
                .add(android.R.id.content, fragment)
                .addToBackStack("settings")
                .commit()
        }
    }
}
