package com.example.carrotnavi

import android.app.Application
import android.content.Context

class CarrotNaviApplication : Application() {

    private var fakeAudioManager: FakeAudioManager? = null

    override fun onCreate() {
        super.onCreate()
        // 앱이 시작될 때 가짜 AudioManager를 초기화
        fakeAudioManager = FakeAudioManager(baseContext)
    }

    override fun getSystemService(name: String): Any? {
        // Tmap SDK 등에서 AUDIO_SERVICE를 요청하면 가짜 매니저를 반환
        if (Context.AUDIO_SERVICE == name && fakeAudioManager != null) {
            return fakeAudioManager
        }
        return super.getSystemService(name)
    }
}
