package com.example.carrotnavi

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Tmap SDK가 안드로이드 시스템에 오디오 포커스를 요청하는 것을 차단하기 위한 가짜 AudioManager.
 * 이 클래스는 AudioManager를 상속받아 오디오 포커스 요청 함수들만 가로채며, 항상 허용(GRANTED)을 반환합니다.
 * 이를 통해 안드로이드 시스템이 Tmap의 오디오 재생 사실을 모르게 하여 백그라운드 음악 볼륨을 줄이지 않게 합니다.
 */
class FakeAudioManager(context: Context) : AudioManager(context) {

    override fun requestAudioFocus(focusRequest: AudioFocusRequest?): Int {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Deprecated("Deprecated in Java")
    override fun requestAudioFocus(
        l: OnAudioFocusChangeListener?,
        streamType: Int,
        durationHint: Int
    ): Int {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandonAudioFocusRequest(focusRequest: AudioFocusRequest?): Int {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Deprecated("Deprecated in Java")
    override fun abandonAudioFocus(l: OnAudioFocusChangeListener?): Int {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
}
