import re

with open("G:/github/CarrotNavi/app/src/main/java/com/example/carrotnavi/KakaoMapActivity.kt", "r", encoding="utf-8") as f:
    content = f.read()

old_methods = """    // KNNaviView_StateDelegate
    override fun naviViewDidUpdateStatusBarColor(p0: Int) {}
    override fun naviViewDidUpdateUseDarkMode(p0: Boolean) {}
    override fun naviViewDidUpdateMapCameraMode(p0: MapViewCameraMode) {}
    override fun naviViewDidUpdateSndVolume(p0: Float) {}
    override fun naviViewDidUpdateCustomButton(p0: Int, p1: Boolean?) {}
    override fun naviViewPopupOpenCheck(p0: Boolean) {}
    override fun naviViewIsArrival(p0: Boolean) {}

    override fun naviViewScreenState(state: KNNaviViewState) {"""

new_methods = """    // KNNaviView_StateDelegate
    override fun naviViewDidUpdateStatusBarColor(aColor: Int) {}
    override fun naviViewDidUpdateUseDarkMode(aMode: Boolean) {}
    override fun naviViewDidUpdateMapCameraMode(aCameraMode: MapViewCameraMode) {}
    override fun naviViewDidUpdateSndVolume(aVolume: Float) {}
    override fun naviViewDidUpdateCustomButton(id: Int, toggleOn: Boolean?) {}
    override fun naviViewPopupOpenCheck(aOpen: Boolean) {}
    override fun naviViewIsArrival(aIsArrival: Boolean) {}

    override fun naviViewScreenState(viewState: KNNaviViewState) {"""

# Replace old methods with new methods
content = content.replace(old_methods, new_methods)

# Also rename the variable in the method body from `state` to `viewState`
content = content.replace('android.util.Log.d("CarrotNavi", "naviViewScreenState: $state")',
                          'android.util.Log.d("CarrotNavi", "naviViewScreenState: $viewState")')
content = content.replace('if (state == KNNaviViewState.NONE)',
                          'if (viewState == KNNaviViewState.NONE)')

with open("G:/github/CarrotNavi/app/src/main/java/com/example/carrotnavi/KakaoMapActivity.kt", "w", encoding="utf-8") as f:
    f.write(content)

print("Signatures updated.")
