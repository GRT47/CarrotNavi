package com.example.carrotnavi

import android.content.Context
import android.media.AudioManager
import android.media.AudioFocusRequest

class TestAudioManager(context: Context) : AudioManager(context) {
    override fun requestAudioFocus(focusRequest: AudioFocusRequest): Int {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}
