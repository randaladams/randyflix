package com.randyflix

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.randyflix.data.MusicLibrary
import com.randyflix.ui.MusicPlayer
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
        MusicLibrary.load(this)
        MusicPlayer.init(this)
        // Music pauses when RandyFlix is left (Home button, another app) — there's no
        // background music service, so it would otherwise be cut off at some random moment.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) {
                started--
                if (started <= 0 && !a.isChangingConfigurations) MusicPlayer.pause()
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
