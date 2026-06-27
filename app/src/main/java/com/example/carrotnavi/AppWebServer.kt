package com.example.carrotnavi

import android.content.Context
import fi.iki.elonen.NanoHTTPD

class AppWebServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val prefs = context.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)

        if (Method.POST == method) {
            try {
                session.parseBody(HashMap())
                val params = session.parameters
                
                val editor = prefs.edit()
                params["TARGET_UDP_IP"]?.firstOrNull()?.let { editor.putString("TARGET_UDP_IP", it) }
                params["TARGET_UDP_PORT"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("TARGET_UDP_PORT", it) }
                params["OVERRIDE_TBT_TURN_TYPE"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("OVERRIDE_TBT_TURN_TYPE", it) }
                params["TEXT_OUTPUT_TARGET"]?.firstOrNull()?.let { editor.putString("TEXT_OUTPUT_TARGET", it) }
                params["TBT_SPACE_BEFORE"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("TBT_SPACE_BEFORE", it) }
                params["TBT_SPACE_AFTER"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("TBT_SPACE_AFTER", it) }
                params["BLOCK_SPEED_OFFSET"]?.firstOrNull()?.toIntOrNull()?.let { editor.putInt("BLOCK_SPEED_OFFSET", it) }
                editor.apply()
                
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Redirect back to GET
            val response = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            response.addHeader("Location", "/")
            return response
        }

        // Handle GET Request
        val targetIp = prefs.getString("TARGET_UDP_IP", "255.255.255.255")
        val targetPort = prefs.getInt("TARGET_UDP_PORT", 7706)
        val overrideTurnType = prefs.getInt("OVERRIDE_TBT_TURN_TYPE", -1)
        val textOutputTarget = prefs.getString("TEXT_OUTPUT_TARGET", "szTBTMainText")
        val spaceBefore = prefs.getInt("TBT_SPACE_BEFORE", 0)
        val spaceAfter = prefs.getInt("TBT_SPACE_AFTER", 0)
        val blockSpeedOffset = prefs.getInt("BLOCK_SPEED_OFFSET", 0)

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
                            <label>TurnType 강제 지정</label>
                            <select name="OVERRIDE_TBT_TURN_TYPE">
                                <option value="-1" ${if(overrideTurnType == -1) "selected" else ""}>끄기 (알림 끄기)</option>
                                <option value="201" ${if(overrideTurnType == 201) "selected" else ""}>목적지 도착 / 경고 팝업 (201)</option>
                                <option value="12" ${if(overrideTurnType == 12) "selected" else ""}>좌회전 (12)</option>
                                <option value="13" ${if(overrideTurnType == 13) "selected" else ""}>우회전 (13)</option>
                                <option value="14" ${if(overrideTurnType == 14) "selected" else ""}>유턴 (14)</option>
                                <option value="7" ${if(overrideTurnType == 7) "selected" else ""}>좌측 분기점 (7)</option>
                                <option value="6" ${if(overrideTurnType == 6) "selected" else ""}>우측 분기점 (6)</option>
                                <option value="102" ${if(overrideTurnType == 102) "selected" else ""}>좌측 램프 진출 (102)</option>
                                <option value="101" ${if(overrideTurnType == 101) "selected" else ""}>우측 램프 진출 (101)</option>
                                <option value="51" ${if(overrideTurnType == 51) "selected" else ""}>직진 / 단순 알림 (51)</option>
                                <option value="153" ${if(overrideTurnType == 153) "selected" else ""}>톨게이트 (153)</option>
                            </select>
                        </div>

                        <div class="form-group">
                            <label>안내 텍스트 출력 위치</label>
                            <div class="radio-group">
                                <input type="radio" id="mainText" name="TEXT_OUTPUT_TARGET" value="szTBTMainText" ${if(textOutputTarget == "szTBTMainText") "checked" else ""}>
                                <label for="mainText">메인 팝업 (szTBTMainText)</label><br>
                                <input type="radio" id="roadName" name="TEXT_OUTPUT_TARGET" value="szPosRoadName" ${if(textOutputTarget == "szPosRoadName") "checked" else ""}>
                                <label for="roadName">도로명 영역 (szPosRoadName)</label>
                            </div>
                        </div>

                        <div class="form-group" style="display:flex; gap:16px;">
                            <div style="flex:1;">
                                <label>앞 여백 (칸 수)</label>
                                <input type="number" name="TBT_SPACE_BEFORE" value="$spaceBefore">
                            </div>
                            <div style="flex:1;">
                                <label>뒤 여백 (칸 수)</label>
                                <input type="number" name="TBT_SPACE_AFTER" value="$spaceAfter">
                            </div>
                        </div>

                        <div class="form-group">
                            <label>구간단속 여유 가속 (km/h)</label>
                            <input type="number" name="BLOCK_SPEED_OFFSET" value="$blockSpeedOffset">
                            <div class="hint">기본값: 0 (구간단속 시 카메라 속도에 추가할 여유 속도)</div>
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
