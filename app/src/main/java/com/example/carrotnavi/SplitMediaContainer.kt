package com.example.carrotnavi

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * 분할화면 미디어 / 오픈파일럿 스크롤 스와이프 제스처 컨테이너
 * - 분할 화면 영역에서 위 또는 아래로 스크롤/스와이프 시 미디어플레이어 ↔ 오픈파일럿 전환
 * - 터치 가로채기(Touch Interception) 및 중첩 스크롤(NestedScrolling)을 모두 지원하여 완벽 동작
 * - 클릭 탭 및 수평 제어(슬라이더 등)는 온전히 보존
 */
class SplitMediaContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), NestedScrollingParent3 {

    var onSwipeVertical: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var totalDy = 0f
    private var isSwipeTriggered = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.2f
    private val minSwipeDistance = 35f * context.resources.displayMetrics.density

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // 자식 뷰가 부모의 수직 스와이프 감지를 방해하지 못하도록 처리
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                totalDy = 0f
                isSwipeTriggered = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(ev.rawX - downX)
                val dy = abs(ev.rawY - downY)
                if (dy > touchSlop && dy > dx * 1.3f) {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isSwipeTriggered = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                totalDy = 0f
                isSwipeTriggered = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val dx = abs(ev.rawX - downX)
                if (abs(dy) >= minSwipeDistance && abs(dy) > dx * 1.2f && !isSwipeTriggered) {
                    isSwipeTriggered = true
                    onSwipeVertical?.invoke()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dy = ev.rawY - downY
                val dx = abs(ev.rawX - downX)
                if (!isSwipeTriggered && abs(dy) >= minSwipeDistance && abs(dy) > dx * 1.2f) {
                    isSwipeTriggered = true
                    onSwipeVertical?.invoke()
                    return true
                }
                isSwipeTriggered = false
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (abs(vScroll) > 0.1f) {
                onSwipeVertical?.invoke()
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // --- NestedScrollingParent3 Implementation ---

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        return (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        totalDy = 0f
        isSwipeTriggered = false
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        if (!isSwipeTriggered && abs(totalDy) >= minSwipeDistance) {
            isSwipeTriggered = true
            onSwipeVertical?.invoke()
        }
        totalDy = 0f
        isSwipeTriggered = false
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        totalDy += (dyConsumed + dyUnconsumed).toFloat()
        if (!isSwipeTriggered && abs(totalDy) >= minSwipeDistance) {
            isSwipeTriggered = true
            onSwipeVertical?.invoke()
        }
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) {
        totalDy += (dyConsumed + dyUnconsumed).toFloat()
        if (!isSwipeTriggered && abs(totalDy) >= minSwipeDistance) {
            isSwipeTriggered = true
            onSwipeVertical?.invoke()
        }
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        totalDy += dy.toFloat()
        if (!isSwipeTriggered && abs(totalDy) >= minSwipeDistance) {
            isSwipeTriggered = true
            onSwipeVertical?.invoke()
            consumed[1] = dy
        }
    }
}
