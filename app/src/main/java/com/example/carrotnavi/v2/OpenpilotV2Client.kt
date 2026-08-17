package com.example.carrotnavi.v2

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class OpenpilotV2Client(
    private val ip: String,
    private val appVersion: String = "11.2.3.3740",
    private val onMapStreamReady: (String, Int, Int) -> Unit // sessionId, streamHandle, manifestRevision
) {
    private val TAG = "OpenpilotV2Client"
    private var controlClient: WebSocketClient? = null
    var renderClient: WebSocketClient? = null
        private set

    private var sessionId: String? = null
    private var manifestRevision: Int = 0
    private var mapMainStreamHandle: Int = 0

    fun start() {
        val uri = URI("ws://$ip:7714/api/navi/ws/v2/control/$appVersion")
        controlClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Control WebSocket Opened")
                sendRequirementsQuery()
            }

            override fun onMessage(message: String?) {
                Log.d(TAG, "Control Message: $message")
                message?.let { handleControlMessage(it) }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "Control WebSocket Closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Control WebSocket Error", ex)
            }
        }
        controlClient?.connect()
    }

    private fun sendRequirementsQuery() {
        try {
            val jsonNames = listOf("vehicle", "guidance_current", "guidance_next", "lane_current", "lane_ahead", "speed", "traffic_signal", "crossroad", "route", "navigation_status", "app_status", "camera_state", "composition_state")
            val imageNames = listOf("tbt_current_compact", "tbt_current_full", "tbt_next", "traffic_signal", "lane_top", "lane_bottom", "safety_primary", "safety_secondary", "safety_section", "crossroad_minimized", "crossroad_expanded", "center_tbt_icon", "center_tbt_text", "center_tbt_fee")
            
            val query = JSONObject().apply {
                put("type", "requirements_query")
                put("protocol_version", 2)
                put("timestamp_ms", System.currentTimeMillis())
                put("app_version", appVersion)
                put("catalog_revision", 1)
                put("limits", JSONObject().apply {
                    put("max_binary_frame_bytes", 8388608)
                    put("max_total_bitrate_kbps", 12000)
                })
                val streamsArray = JSONArray()
                for (name in jsonNames) {
                    streamsArray.put(JSONObject().apply {
                        put("kind", "json")
                        put("name", name)
                        put("schema_version", 1)
                    })
                }
                for (name in imageNames) {
                    streamsArray.put(JSONObject().apply {
                        put("kind", "image")
                        put("name", name)
                        put("schema_version", 1)
                    })
                }
                streamsArray.put(JSONObject().apply {
                    put("kind", "render")
                    put("name", "map_main")
                    put("schema_version", 1)
                    put("nullable", false)
                    put("supported_params", JSONObject().apply {
                        put("codec", JSONArray().put("h264"))
                        put("width", JSONObject().put("min", 160).put("max", 1280).put("default", 960))
                        put("height", JSONObject().put("min", 120).put("max", 720).put("default", 540))
                        put("fps", JSONObject().put("min", 1).put("max", 60).put("default", 10))
                        put("h264_bitrate_kbps", JSONObject().put("min", 1).put("max", 12000).put("default", 3000))
                    })
                })
                put("streams", streamsArray)
            }
            controlClient?.send(query.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending requirements query", e)
        }
    }

    private fun handleControlMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "subscription_manifest" -> {
                    sessionId = json.optString("session_id")
                    manifestRevision = json.optInt("revision")
                    
                    val streams = json.optJSONArray("streams")
                    var foundMapMain = false
                    
                    if (streams != null) {
                        for (i in 0 until streams.length()) {
                            val stream = streams.optJSONObject(i)
                            if (stream.optString("kind") == "render" && stream.optString("name") == "map_main") {
                                mapMainStreamHandle = stream.optInt("stream_handle")
                                foundMapMain = true
                            }
                        }
                    }

                    sendManifestApplied(json)

                    if (foundMapMain && sessionId != null) {
                        startRenderClient()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing control message", e)
        }
    }

    private fun sendManifestApplied(manifestJson: JSONObject) {
        try {
            val response = JSONObject().apply {
                put("type", "manifest_applied")
                put("protocol_version", 2)
                put("timestamp_ms", System.currentTimeMillis())
                put("session_id", sessionId)
                put("revision", manifestRevision)
                put("effective_config", manifestJson.optJSONArray("streams") ?: JSONArray())
            }
            controlClient?.send(response.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending manifest_applied", e)
        }
    }

    private fun startRenderClient() {
        val uri = URI("ws://$ip:7714/api/navi/ws/v2/render/$sessionId/map_main")
        renderClient = object : WebSocketClient(uri) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                Log.d(TAG, "Render WebSocket Opened")
                onMapStreamReady(sessionId!!, mapMainStreamHandle, manifestRevision)
            }

            override fun onMessage(message: String?) {
                // Not expected to receive messages on render stream
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.d(TAG, "Render WebSocket Closed: $reason")
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "Render WebSocket Error", ex)
            }
        }
        renderClient?.connect()
    }

    fun stop() {
        controlClient?.close()
        renderClient?.close()
    }
}
