package com.aura.assistant

import android.app.Application

class AuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Warm up persistent singleton TextToSpeech engine
        AuraTTSManager.getInstance(this)
    }
}
