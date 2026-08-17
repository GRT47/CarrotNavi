package com.example.carrotnavi.v2

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import org.java_websocket.client.WebSocketClient
import java.nio.ByteBuffer

class VideoStreamingManager(
    private val context: Context,
    private val renderClient: WebSocketClient,
    private val sessionId: String,
    private val streamHandle: Int,
    private val manifestRevision: Int
) {
    private val TAG = "VideoStreamingManager"
    
    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var sequence: Long = 0
    
    private val width = 960
    private val height = 540
    private val dpi = 360
    private val fps = 10
    private val bitrate = 3000 * 1000 // 3 Mbps

    var display = virtualDisplay?.display
        get() = virtualDisplay?.display

    fun start() {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            
            mediaCodec?.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Not used for Surface input
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (outputBuffer != null && info.size > 0) {
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyFrame = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        
                        val msgType = if (isConfig) 2 else 3 // 2=VIDEO_CONFIG, 3=VIDEO_ACCESS_UNIT
                        val flags = if (isKeyFrame || isConfig) 1 else 0

                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)

                        val payload = ByteArray(info.size)
                        outputBuffer.get(payload)

                        sendCnv2Frame(msgType, flags, payload)
                    }
                    codec.releaseOutputBuffer(index, false)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "MediaCodec Error", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.d(TAG, "Output format changed: $format")
                }
            })

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            if (inputSurface != null) {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                
                virtualDisplay = displayManager.createVirtualDisplay(
                    "carrot_map_main", width, height, dpi, inputSurface, flags
                )
                
                Log.d(TAG, "VirtualDisplay created with id: ${virtualDisplay?.display?.displayId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VideoStreamingManager", e)
        }
    }

    private fun sendCnv2Frame(msgType: Int, flags: Int, payload: ByteArray) {
        if (!renderClient.isOpen) return

        val headerSize = 40
        val buffer = ByteBuffer.allocate(headerSize + payload.size)
        
        // 0-3: "CNV2"
        buffer.put("CNV2".toByteArray())
        // 4: Protocol version (2)
        buffer.put(2.toByte())
        // 5: Message type
        buffer.put(msgType.toByte())
        // 6: Format code (3 = H264_ANNEX_B)
        buffer.put(3.toByte())
        // 7: Flags
        buffer.put(flags.toByte())
        // 8-11: stream_handle
        buffer.putInt(streamHandle)
        // 12-15: manifest_revision
        buffer.putInt(manifestRevision)
        // 16-23: sequence
        buffer.putLong(sequence++)
        // 24-31: source_timestamp_ms
        buffer.putLong(System.currentTimeMillis())
        // 32-35: payload length
        buffer.putInt(payload.size)
        // 36-37: width
        buffer.putShort(width.toShort())
        // 38-39: height
        buffer.putShort(height.toShort())

        buffer.put(payload)
        
        try {
            renderClient.send(buffer.array())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending frame", e)
        }
    }

    fun stop() {
        try {
            virtualDisplay?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping", e)
        }
    }
}
