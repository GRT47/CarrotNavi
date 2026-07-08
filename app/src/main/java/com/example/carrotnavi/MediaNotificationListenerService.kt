package com.example.carrotnavi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log

class MediaNotificationListenerService : NotificationListenerService() {
    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null

    companion object {
        const val ACTION_MEDIA_UPDATE = "com.example.carrotnavi.ACTION_MEDIA_UPDATE"
        const val ACTION_MEDIA_CONTROL = "com.example.carrotnavi.ACTION_MEDIA_CONTROL"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_IS_PLAYING = "isPlaying"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
        
        var currentTitle: String = "재생중인 곡 없음"
        var currentArtist: String = "아티스트 없음"
        var currentAlbumArt: Bitmap? = null
        var isPlaying: Boolean = false
        var position: Long = 0L
        var duration: Long = 0L
        var lastUpdateTime: Long = 0L
    }

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MEDIA_CONTROL) {
                when (intent.getStringExtra("command")) {
                    "play" -> currentController?.transportControls?.play()
                    "pause" -> currentController?.transportControls?.pause()
                    "next" -> currentController?.transportControls?.skipToNext()
                    "prev" -> currentController?.transportControls?.skipToPrevious()
                    "seek" -> {
                        val seekPos = intent.getLongExtra("seekPos", -1)
                        if (seekPos >= 0) currentController?.transportControls?.seekTo(seekPos)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        
        val filter = android.content.IntentFilter(ACTION_MEDIA_CONTROL)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MediaService", "Listener Connected")
        setupMediaSessionCallback()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // 미디어 알림이 업데이트될 때 (곡이 바뀔 때 등) 세션 정보를 다시 확인합니다.
        if (sbn?.notification?.extras?.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION) == true) {
            try {
                val component = ComponentName(this, MediaNotificationListenerService::class.java)
                val controllers = mediaSessionManager?.getActiveSessions(component)
                updateControllers(controllers)
            } catch (e: SecurityException) {
                // Ignore
            }
        }
    }

    private fun setupMediaSessionCallback() {
        try {
            val component = ComponentName(this, MediaNotificationListenerService::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(component)
            
            mediaSessionManager?.addOnActiveSessionsChangedListener({ newControllers ->
                updateControllers(newControllers)
            }, component)
            
            if (controllers != null) {
                updateControllers(controllers)
            }
        } catch (e: SecurityException) {
            Log.e("MediaService", "Permission not granted: ${e.message}")
        }
    }

    private fun updateControllers(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) return
        
        val playing = controllers.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
            
        // 세션 토큰이 다를 때만 콜백을 다시 등록합니다.
        if (currentController?.sessionToken != playing?.sessionToken) {
            currentController?.unregisterCallback(mediaCallback)
            currentController = playing
            currentController?.registerCallback(mediaCallback)
        }
        
        // 항상 최신 메타데이터로 갱신을 시도합니다.
        currentController?.metadata?.let { mediaCallback.onMetadataChanged(it) }
        currentController?.playbackState?.let { mediaCallback.onPlaybackStateChanged(it) }
    }

    private fun broadcastMediaState() {
        val intent = Intent(ACTION_MEDIA_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_TITLE, currentTitle)
            putExtra(EXTRA_ARTIST, currentArtist)
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_POSITION, position)
            putExtra(EXTRA_DURATION, duration)
        }
        sendBroadcast(intent)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) 
                ?: "알 수 없는 제목"
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) 
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_WRITER)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: "아티스트 없음"
            currentTitle = title
            currentArtist = artist
            currentAlbumArt = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            
            broadcastMediaState()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            isPlaying = state?.state == PlaybackState.STATE_PLAYING
            position = state?.position ?: 0L
            lastUpdateTime = android.os.SystemClock.elapsedRealtime()
            
            broadcastMediaState()
        }
    }
}
