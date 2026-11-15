package com.ukdevilz

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class UKDevilzSettings : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)

        val marizaToggle = SwitchPreferenceCompat(context).apply {
            key = "ukd_mariza_rabbit"
            title = "Show Mariza Rabbit"
            setDefaultValue(true)
        }
        
        val violetToggle = SwitchPreferenceCompat(context).apply {
            key = "ukd_violet_myers"
            title = "Show Violet Myers"
            setDefaultValue(true)
        }

        val victoriaToggle = SwitchPreferenceCompat(context).apply {
            key = "ukd_victoria_june"
            title = "Show Victoria June"
            setDefaultValue(true)
        }

        val yashmineToggle = SwitchPreferenceCompat(context).apply {
            key = "ukd_yashmine_khan"
            title = "Show Yashmine Khan"
            setDefaultValue(true)
        }

        screen.addPreference(marizaToggle)
        screen.addPreference(violetToggle)
        screen.addPreference(victoriaToggle)
        screen.addPreference(yashmineToggle)

        preferenceScreen = screen
    }
}
