package com.example.carrotnavi

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.round

/**
 * 지도(TMAP/카카오)와 미디어 플레이어 사이의 경계선 핸들(Split Handle)을
 * 터치/드래그하여 분할 비율을 실시간으로 조절하고,
 * 탭하여 전체화면 ↔ 분할화면을 토글하는 매니저 클래스.
 */
class SplitHandleManager(
    private val activity: Activity,
    private val mainContainer: LinearLayout,
    private val mapContainer: FrameLayout,
    private val mediaContainer: FrameLayout,
    private val splitHandle: FrameLayout,
    private val indicatorView: View,
    private val onLayoutRefreshNeeded: (Float) -> Unit
) {
    private val sharedPref = activity.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startRatio = 3.5f
    private var currentLiveRatio = 3.5f
    private var isDragging = false

    init {
        setupTouchListener()
    }

    private fun isPortrait(): Boolean {
        return activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    private fun getRatioKey(): String {
        return if (isPortrait()) "MEDIA_SPLIT_RATIO_PORTRAIT_F" else "MEDIA_SPLIT_RATIO_LANDSCAPE_F"
    }

    private fun getLastOpenRatioKey(): String {
        return if (isPortrait()) "MEDIA_LAST_OPEN_RATIO_PORTRAIT_F" else "MEDIA_LAST_OPEN_RATIO_LANDSCAPE_F"
    }

    fun getCurrentRatio(): Float {
        val key = getRatioKey()
        return if (sharedPref.contains(key)) {
            sharedPref.getFloat(key, 3.5f)
        } else if (sharedPref.contains("MEDIA_SPLIT_RATIO_F")) {
            sharedPref.getFloat("MEDIA_SPLIT_RATIO_F", 3.5f)
        } else {
            val oldRatio = sharedPref.getInt("MEDIA_SPLIT_RATIO", 4).toFloat()
            if (oldRatio >= 5f) 5f else oldRatio
        }
    }

    /**
     * 화면 회전 또는 레이아웃 업데이트 시 핸들의 가로/세로 크기 및 인디케이터 모양 갱신
     */
    fun updateHandleLayout(orientation: Int) {
        val density = activity.resources.displayMetrics.density
        val handleThickness = (20 * density).toInt()

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val handleParams = splitHandle.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(handleThickness, LinearLayout.LayoutParams.MATCH_PARENT)
            handleParams.width = handleThickness
            handleParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            handleParams.weight = 0f
            splitHandle.layoutParams = handleParams

            val indParams = indicatorView.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams((4 * density).toInt(), (48 * density).toInt())
            indParams.width = (4 * density).toInt()
            indParams.height = (48 * density).toInt()
            indicatorView.layoutParams = indParams
        } else {
            val handleParams = splitHandle.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, handleThickness)
            handleParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            handleParams.height = handleThickness
            handleParams.weight = 0f
            splitHandle.layoutParams = handleParams

            val indParams = indicatorView.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams((48 * density).toInt(), (4 * density).toInt())
            indParams.width = (48 * density).toInt()
            indParams.height = (4 * density).toInt()
            indicatorView.layoutParams = indParams
        }
    }

    private fun setupTouchListener() {
        splitHandle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startRatio = getCurrentRatio()
                    currentLiveRatio = startRatio
                    isDragging = false
                    indicatorView.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val portrait = isPortrait()
                    val totalSize = if (portrait) mainContainer.height.toFloat() else mainContainer.width.toFloat()
                    val diff = if (portrait) (event.rawY - downRawY) else (event.rawX - downRawX)

                    if (!isDragging && abs(diff) > touchSlop) {
                        isDragging = true
                    }

                    if (isDragging && totalSize > 0f) {
                        val deltaRatio = (diff / totalSize) * 5.0f
                        val newRatio = (startRatio + deltaRatio).coerceIn(1.0f, 5.0f)
                        currentLiveRatio = newRatio
                        applyLiveRatio(newRatio, portrait)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    indicatorView.isPressed = false
                    if (!isDragging) {
                        // 단순 탭: 전체화면(미디어 닫기) ↔ 이전 비율 복원 토글
                        handleSingleTap()
                    } else {
                        // 드래그 종료: 스냅 및 비율 영구 저장
                        handleDragEnd(currentLiveRatio)
                    }
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }

                else -> false
            }
        }
    }

    private fun applyLiveRatio(ratio: Float, portrait: Boolean) {
        val mediaWeight = 5.0f - ratio
        if (mediaWeight <= 0.05f) {
            mediaContainer.visibility = View.GONE
        } else {
            mediaContainer.visibility = View.VISIBLE
        }

        if (portrait) {
            val mapParams = mapContainer.layoutParams as LinearLayout.LayoutParams
            mapParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            mapParams.height = 0
            mapParams.weight = ratio
            mapContainer.layoutParams = mapParams

            val mediaParams = mediaContainer.layoutParams as LinearLayout.LayoutParams
            mediaParams.width = LinearLayout.LayoutParams.MATCH_PARENT
            mediaParams.height = 0
            mediaParams.weight = mediaWeight
            mediaContainer.layoutParams = mediaParams
        } else {
            val mapParams = mapContainer.layoutParams as LinearLayout.LayoutParams
            mapParams.width = 0
            mapParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            mapParams.weight = ratio
            mapContainer.layoutParams = mapParams

            val mediaParams = mediaContainer.layoutParams as LinearLayout.LayoutParams
            mediaParams.width = 0
            mediaParams.height = LinearLayout.LayoutParams.MATCH_PARENT
            mediaParams.weight = mediaWeight
            mediaContainer.layoutParams = mediaParams
        }
        mainContainer.requestLayout()
    }

    private fun handleSingleTap() {
        val cur = getCurrentRatio()
        val targetRatio: Float
        if (cur < 4.8f) {
            // 현재 열려 있음 -> 최근 열린 비율 저장 후 전체화면(5.0f)으로 닫기
            sharedPref.edit().putFloat(getLastOpenRatioKey(), cur).apply()
            targetRatio = 5.0f
        } else {
            // 현재 닫혀 있음 -> 이전 비율 복원 (없으면 기본 3.5f)
            val lastOpen = sharedPref.getFloat(getLastOpenRatioKey(), 3.5f)
            targetRatio = if (lastOpen in 1.0f..4.5f) lastOpen else 3.5f
        }
        commitRatio(targetRatio)
    }

    private fun handleDragEnd(rawRatio: Float) {
        // 4.6 이상이면 완전 닫힘(5.0f)으로 스냅
        val snappedRatio = if (rawRatio >= 4.6f) {
            5.0f
        } else {
            // 0.5 단위로 부드럽게 스냅 (예: 2.0, 2.5, 3.0, 3.5, 4.0, 4.5)
            val rounded = (round(rawRatio * 2f) / 2f).coerceIn(1.0f, 4.5f)
            // 열린 상태면 최근 열린 비율로 기억
            sharedPref.edit().putFloat(getLastOpenRatioKey(), rounded).apply()
            rounded
        }
        commitRatio(snappedRatio)
    }

    private fun commitRatio(ratio: Float) {
        val ratioKey = getRatioKey()
        sharedPref.edit()
            .putFloat(ratioKey, ratio)
            .putFloat("MEDIA_SPLIT_RATIO_F", ratio)
            .apply()

        // 액티비티의 UI 콜백 호출
        onLayoutRefreshNeeded(ratio)
    }
}
