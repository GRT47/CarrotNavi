package com.example.carrotnavi

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

object VoiceDuckingManager {
    @Volatile var enabled: Boolean = true
    private val activeSources = mutableSetOf<String>()
    private var audioFocusRequestObj: Any? = null
    private val handler = Handler(Looper.getMainLooper())
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { _ -> }
    
    private var wasPausedByUs = false
    private var appContext: Context? = null
    
    private val endDuckingRunnable = Runnable {
        Log.d("VoiceDucking", "duck END (tail)")
        releaseFocus()
    }
    
    private val watchdogRunnable = Runnable {
        Log.w("VoiceDucking", "duck END (watchdog) - forced release")
        releaseFocus()
    }

    private var audioManager: AudioManager? = null
    
    // 무음 오디오 재생을 위한 트랙 (Android 12+ 우회용)
    private var silentAudioTrack: android.media.AudioTrack? = null

    @Synchronized
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    @Synchronized
    fun onVoiceStart(am: AudioManager, source: String) {
        if (!enabled) return
        if (appContext == null) {
            try {
                val thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
                val app = thread.javaClass.getMethod("getApplication").invoke(thread) as android.app.Application
                appContext = app.applicationContext
            } catch (e: Exception) {
                Log.e("VoiceDucking", "Failed to get Application Context via reflection", e)
            }
        }
        audioManager = am
        
        val ctx = appContext ?: return
        val sp = ctx.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
        val mode = sp.getInt("AUDIO_DUCKING_MODE", 1) // 0: None, 1: Duck, 2: Pause
        if (mode == 0) return

        val wasEmpty = activeSources.isEmpty()
        activeSources.add(source)
        
        handler.removeCallbacks(endDuckingRunnable)
        handler.removeCallbacks(watchdogRunnable)
        
        if (wasEmpty) {
            Log.d("VoiceDucking", "duck START (mode: $mode)")
            // 샤오미 등에서는 OS에 실제로 AudioFocus를 요청하지 않으면 앱 오디오 자체가 Mute 되는 경우가 있으므로,
            // mode가 2(일시정지)이더라도 시스템에 포커스 요청은 보냅니다.
            requestFocus(am)
            
            if (mode == 2) {
                forcePauseMedia()
            }
        }
        
        // 워치독 10초 설정
        handler.postDelayed(watchdogRunnable, 10000)
    }

    @Synchronized
    fun onVoiceEnd(source: String) {
        if (!enabled) return
        activeSources.remove(source)
        
        if (activeSources.isEmpty()) {
            // 꼬리 딜레이 500ms
            handler.removeCallbacks(watchdogRunnable)
            handler.postDelayed(endDuckingRunnable, 500)
        }
    }

    private fun forcePauseMedia() {
        handler.post {
            if (MediaNotificationListenerService.isPlaying) {
                wasPausedByUs = true
                appContext?.let { ctx ->
                    val intent = Intent(MediaNotificationListenerService.ACTION_MEDIA_CONTROL)
                    intent.putExtra("command", "pause")
                    intent.setPackage(ctx.packageName)
                    ctx.sendBroadcast(intent)
                    Log.d("VoiceDucking", "Forced Media PAUSE sent")
                }
            }
        }
    }

    private fun resumeMediaIfPaused() {
        if (wasPausedByUs) {
            wasPausedByUs = false
            appContext?.let { ctx ->
                val intent = Intent(MediaNotificationListenerService.ACTION_MEDIA_CONTROL)
                intent.putExtra("command", "play")
                intent.setPackage(ctx.packageName)
                ctx.sendBroadcast(intent)
                Log.d("VoiceDucking", "Forced Media PLAY sent")
            }
        }
    }

    private fun requestFocus(am: AudioManager) {
        handler.post {
            if (activeSources.isEmpty()) return@post
            
            // Android 12+ 해킹 우회: 포커스 요청 시점에 오디오가 재생 중이어야 시스템이 ducking을 허용함
            try {
                if (silentAudioTrack == null) {
                    val minSize = android.media.AudioTrack.getMinBufferSize(
                        16000,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                    )
                    val audioTrack = android.media.AudioTrack(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                        android.media.AudioFormat.Builder()
                            .setSampleRate(16000)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                        minSize,
                        android.media.AudioTrack.MODE_STATIC,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    val silentBuffer = ShortArray(minSize / 2)
                    audioTrack.write(silentBuffer, 0, silentBuffer.size)
                    audioTrack.setLoopPoints(0, silentBuffer.size, -1)
                    audioTrack.play()
                    silentAudioTrack = audioTrack
                } else {
                    silentAudioTrack?.play()
                }
            } catch (e: Exception) {
                Log.e("VoiceDucking", "Silent AudioTrack failed", e)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusChangeListener, handler)
                    .build()
                audioFocusRequestObj = focusRequest
                val res = am.requestAudioFocus(focusRequest)
                Log.d("VoiceDucking", "requestAudioFocus returned: $res")
                
                if (res == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    @Suppress("DEPRECATION")
                    val fallbackRes = am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    Log.d("VoiceDucking", "fallback requestAudioFocus returned: $fallbackRes")

                    // 모드가 1(볼륨 줄이기)일 때, 포커스 요청이 실패하면 강제 일시정지로 우회
                    if (fallbackRes == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                        val ctx = appContext
                        if (ctx != null) {
                            val duckingMode = ctx.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE).getInt("AUDIO_DUCKING_MODE", 1)
                            if (duckingMode == 1) {
                                Log.d("VoiceDucking", "Audio focus completely denied! Falling back to Pause mode.")
                                forcePauseMedia()
                            }
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val res = am.requestAudioFocus(focusChangeListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                Log.d("VoiceDucking", "requestAudioFocus returned: $res")
                if (res == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                    val ctx = appContext
                    if (ctx != null) {
                        val duckingMode = ctx.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE).getInt("AUDIO_DUCKING_MODE", 1)
                        if (duckingMode == 1) {
                            Log.d("VoiceDucking", "Audio focus completely denied! Falling back to Pause mode.")
                            forcePauseMedia()
                        }
                    }
                }
            }
        }
    }

    private fun releaseFocus() {
        activeSources.clear()
        
        try {
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
        } catch (e: Exception) {}
        silentAudioTrack = null

        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (audioFocusRequestObj as? AudioFocusRequest)?.let {
                    am.abandonAudioFocusRequest(it)
                }
                audioFocusRequestObj = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        }
        
        resumeMediaIfPaused()
    }
}
