package com.randyflix

import android.app.Application
import com.randyflix.data.Backgrounds
import com.randyflix.data.Library
import com.randyflix.data.LiveTv
import com.randyflix.data.Playback

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Playback.init(this)
        Library.load(this)
        LiveTv.load(this)
        Backgrounds.load(this)
    }
}
