import re

with open("G:/github/CarrotNavi/app/src/main/java/com/example/carrotnavi/KakaoMapActivity.kt", "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add imports
imports = """import com.kakaomobility.knsdk.ui.view.KNNaviView_StateDelegate
import com.kakaomobility.knsdk.ui.view.KNNaviViewState
import com.kakaomobility.knsdk.ui.component.MapViewCameraMode
"""
content = re.sub(r'(import com\.kakaomobility\.knsdk\.guidance\.knguidance\.\*\n)', r'\1' + imports, content)

# 2. Add KNNaviView_StateDelegate to class signature
content = content.replace("KNGuidance_CitsGuideDelegate {", "KNGuidance_CitsGuideDelegate,\n    KNNaviView_StateDelegate {")

# 3. Add stateDelegate assignment in startSafeDrivingMode
assignment = """            citsGuideDelegate      = this@KakaoMapActivity
            naviView.stateDelegate = this@KakaoMapActivity"""
content = content.replace("            citsGuideDelegate      = this@KakaoMapActivity", assignment)

# 4. Remove previous hack in guidanceGuideEnded
old_guidance_ended = """    override fun guidanceGuideEnded(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideEnded(guidance)
        
        // 무력화: 주행종료 버튼을 누르거나 안내가 종료되면 즉시 안전운행모드로 복귀
        if (!isFinishing) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (!isFinishing) {
                    try {
                        KNSDK.sharedGuidance()?.stop()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    startSafeDrivingMode()
                }
            }, 1500)
        }
    }"""
new_guidance_ended = """    override fun guidanceGuideEnded(guidance: KNGuidance) {
        if(::naviView.isInitialized) naviView.guidanceGuideEnded(guidance)
    }"""
content = content.replace(old_guidance_ended, new_guidance_ended)

# 5. Add KNNaviView_StateDelegate methods
delegate_methods = """
    // KNNaviView_StateDelegate
    override fun naviViewDidUpdateStatusBarColor(p0: Int) {}
    override fun naviViewDidUpdateUseDarkMode(p0: Boolean) {}
    override fun naviViewDidUpdateMapCameraMode(p0: MapViewCameraMode) {}
    override fun naviViewDidUpdateSndVolume(p0: Float) {}
    override fun naviViewDidUpdateCustomButton(p0: Int, p1: Boolean?) {}
    override fun naviViewPopupOpenCheck(p0: Boolean) {}
    override fun naviViewIsArrival(p0: Boolean) {}

    override fun naviViewScreenState(state: KNNaviViewState) {
        android.util.Log.d("CarrotNavi", "naviViewScreenState: $state")
        if (state == KNNaviViewState.NONE) {
            // 주행 종료 버튼을 눌러 일반 지도 상태(NONE)가 되었을 때,
            // 즉시 안전운행 모드로 다시 복귀시킵니다.
            if (!isFinishing) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (!isFinishing) {
                        try { KNSDK.sharedGuidance()?.stop() } catch (e: Exception) {}
                        startSafeDrivingMode()
                    }
                }, 500)
            }
        }
    }
"""

# Insert before the last brace
content = re.sub(r'(\n}\s*)$', delegate_methods + r'\1', content)

with open("G:/github/CarrotNavi/app/src/main/java/com/example/carrotnavi/KakaoMapActivity.kt", "w", encoding="utf-8") as f:
    f.write(content)

print("Patched KakaoMapActivity.kt successfully.")
