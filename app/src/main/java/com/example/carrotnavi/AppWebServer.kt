package com.example.carrotnavi

import android.content.Context
import android.content.Intent
import fi.iki.elonen.NanoHTTPD

class AppWebServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)

        if (Method.POST == method) {
            try {
                session.parseBody(HashMap())
                val params = session.parameters
                
                
                if (session.uri == "/api/search") {
                    val searchText = params["search_text"]?.firstOrNull()
                    if (!searchText.isNullOrEmpty()) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, searchText)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                setPackage(context.packageName)
                            }
                            context.startActivity(sendIntent)
                        }
                    }
                }
                
                val editor = prefs.edit()
                
                if (params.containsKey("TARGET_UDP_IP")) {
                    params["TARGET_UDP_IP"]?.firstOrNull()?.let { editor.putString("TARGET_UDP_IP", it) }
                }
                if (params.containsKey("TARGET_UDP_PORT")) {
                    params["TARGET_UDP_PORT"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("TARGET_UDP_PORT", it) }
                }
                if (params.containsKey("DEBUG_OVERLAY_VISIBLE")) {
                    val debugVisible = params["DEBUG_OVERLAY_VISIBLE"]?.firstOrNull() == "true"
                    editor.putBoolean("DEBUG_OVERLAY_VISIBLE", debugVisible)
                }
                if (params.containsKey("BLOCK_SPEED_ENABLED")) {
                    val boostEnabled = params["BLOCK_SPEED_ENABLED"]?.firstOrNull() == "true"
                    editor.putBoolean("BLOCK_SPEED_ENABLED", boostEnabled)
                }
                if (params.containsKey("BLOCK_SPEED_OFFSET")) {
                    params["BLOCK_SPEED_OFFSET"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("BLOCK_SPEED_OFFSET", it) }
                }
                if (params.containsKey("BLOCK_SPEED_FAKE_DROP")) {
                    params["BLOCK_SPEED_FAKE_DROP"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("BLOCK_SPEED_FAKE_DROP", it) }
                }
                if (params.containsKey("BLOCK_SPEED_BOOST_MODE")) {
                    params["BLOCK_SPEED_BOOST_MODE"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("BLOCK_SPEED_BOOST_MODE", it) }
                }
                editor.apply()
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            if (session.uri == "/api/search") {
                val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                response.addHeader("Access-Control-Allow-Origin", "*")
                return response
            }
            
            // Redirect back to GET
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            response.addHeader("Location", "/")
            return response
        }

        // Handle GET Request
        val targetIp = prefs.getString("TARGET_UDP_IP", "255.255.255.255")
        val targetPort = prefs.getInt("TARGET_UDP_PORT", 7706)
        val isDebugOverlayVisible = prefs.getBoolean("DEBUG_OVERLAY_VISIBLE", false)
        val blockSpeedEnabled = prefs.getBoolean("BLOCK_SPEED_ENABLED", false)
        val blockSpeedOffset = prefs.getInt("BLOCK_SPEED_OFFSET", 0)
        val blockSpeedFakeDrop = prefs.getInt("BLOCK_SPEED_FAKE_DROP", 10)
        val blockSpeedBoostMode = prefs.getInt("BLOCK_SPEED_BOOST_MODE", 0)

        if (session.uri == "/api/settings") {
            val json = """{"TARGET_UDP_IP":"$targetIp", "TARGET_UDP_PORT":$targetPort, "DEBUG_OVERLAY_VISIBLE":$isDebugOverlayVisible, "BLOCK_SPEED_ENABLED":$blockSpeedEnabled, "BLOCK_SPEED_OFFSET":$blockSpeedOffset, "BLOCK_SPEED_FAKE_DROP":$blockSpeedFakeDrop, "BLOCK_SPEED_BOOST_MODE":$blockSpeedBoostMode}"""
            val response = newFixedLengthResponse(Response.Status.OK, "application/json", json)
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>CarrotNavi 원격 설정</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; background: #f4f4f9; padding: 20px; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; background: #fff; padding: 30px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); }
                    h2 { text-align: center; color: #ff6f00; margin-bottom: 24px; }
                    .form-group { margin-bottom: 20px; }
                    label { display: block; font-weight: bold; margin-bottom: 8px; color: #555; }
                    input[type="text"], input[type="number"], select { width: 100%; padding: 12px; border: 1px solid #ccc; border-radius: 8px; box-sizing: border-box; font-size: 16px; }
                    .radio-group { padding: 8px 0; }
                    .radio-group label { font-weight: normal; display: inline-block; margin-right: 16px; }
                    button { width: 100%; padding: 14px; background-color: #ff6f00; color: white; border: none; border-radius: 8px; font-size: 18px; font-weight: bold; cursor: pointer; margin-top: 10px; }
                    button:hover { background-color: #e66400; }
                    .hint { font-size: 12px; color: #888; margin-top: 4px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h2>🥕 CarrotNavi 원격 설정</h2>
                    <form method="POST" action="/">
                        
                        <div class="form-group">
                            <label>UDP 대상 IP</label>
                            <input type="text" name="TARGET_UDP_IP" value="$targetIp">
                            <div class="hint">기본값: 255.255.255.255</div>
                        </div>

                        <div class="form-group">
                            <label>UDP 대상 포트</label>
                            <input type="number" name="TARGET_UDP_PORT" value="$targetPort">
                            <div class="hint">기본값: 7706</div>
                        </div>

                        <div class="form-group">
                            <label>디버그 오버레이 표시</label>
                            <div class="radio-group">
                                <input type="radio" id="debugOn" name="DEBUG_OVERLAY_VISIBLE" value="true" ${if(isDebugOverlayVisible) "checked" else ""}>
                                <label for="debugOn">켜기</label><br>
                                <input type="radio" id="debugOff" name="DEBUG_OVERLAY_VISIBLE" value="false" ${if(!isDebugOverlayVisible) "checked" else ""}>
                                <label for="debugOff">끄기</label>
                            </div>
                        </div>

                        <div class="form-group">
                            <label>구간단속 여유 가속 (km/h)</label>
                            <input type="number" name="BLOCK_SPEED_OFFSET" value="$blockSpeedOffset">
                            <div class="hint">기본값: 0 (구간단속 시 오픈파일럿에 부여할 최대 펀치력/추가 가속력)</div>
                        </div>

                        <div class="form-group">
                            <label>목표 평균속도 상향값 (km/h)</label>
                            <input type="number" name="BLOCK_SPEED_FAKE_DROP" value="$blockSpeedFakeDrop">
                            <div class="hint">기본값: 10 (제한속도 대비 평균속도를 얼마나 더 높게 유지할 것인지)</div>
                        </div>

                        <div class="form-group">
                            <label>가속 방식 (보상 가속 모드)</label>
                            <div class="radio-group">
                                <input type="radio" id="boostProg" name="BLOCK_SPEED_BOOST_MODE" value="0" ${if(blockSpeedBoostMode == 0) "checked" else ""}>
                                <label for="boostProg">점진적 가속 (목표속도 도달 시 정지)</label><br>
                                <input type="radio" id="boostFixed" name="BLOCK_SPEED_BOOST_MODE" value="1" ${if(blockSpeedBoostMode == 1) "checked" else ""}>
                                <label for="boostFixed">고정 가속 (강제 풀가속)</label>
                            </div>
                        </div>

                        <button type="submit">설정 저장</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
    }
}
