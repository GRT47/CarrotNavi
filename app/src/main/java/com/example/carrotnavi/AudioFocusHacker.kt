package com.example.carrotnavi

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

object AudioFocusHacker {
    @JvmStatic
    fun requestAudioFocus(
        am: AudioManager,
        l: AudioManager.OnAudioFocusChangeListener,
        streamType: Int,
        durationHint: Int
    ): Int {
        Log.d("TmapVolume", "[AudioFocusHacker] requestAudioFocus(old) intercepted! Routing to VoiceDuckingManager.")
        VoiceDuckingManager.onVoiceStart(am, "tmap_old")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun requestAudioFocus(
        am: AudioManager,
        request: AudioFocusRequest
    ): Int {
        Log.d("TmapVolume", "[AudioFocusHacker] requestAudioFocus(new) intercepted! Routing to VoiceDuckingManager.")
        VoiceDuckingManager.onVoiceStart(am, "tmap_new")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun abandonAudioFocus(
        am: AudioManager,
        l: AudioManager.OnAudioFocusChangeListener
    ): Int {
        Log.d("TmapVolume", "[AudioFocusHacker] abandonAudioFocus(old) intercepted! Routing to VoiceDuckingManager.")
        VoiceDuckingManager.onVoiceEnd("tmap_old")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @JvmStatic
    fun abandonAudioFocusRequest(
        am: AudioManager,
        request: AudioFocusRequest
    ): Int {
        Log.d("TmapVolume", "[AudioFocusHacker] abandonAudioFocusRequest(new) intercepted! Routing to VoiceDuckingManager.")
        VoiceDuckingManager.onVoiceEnd("tmap_new")
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}
