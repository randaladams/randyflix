package com.randyflix.ui

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.randyflix.R
import com.randyflix.Settings
import com.randyflix.data.Library

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, MainFragment()).commit()
        }
        if (!Settings.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            Library.scanIfStale()
        }
    }
}
