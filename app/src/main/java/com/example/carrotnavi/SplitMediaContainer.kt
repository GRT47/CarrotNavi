package com.example.carrotnavi

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * 분할화면 미디어 ↔ 오픈파일럿 스크롤 애니메이션 컨테이너
 * - 사용자가 위/아래로 스크롤/스와이프 시 단순히 바뀌는 것이 아니라
 *   상하 이동 스크롤 애니메이션(TranslationY)으로 두 화면이 부드럽게 전환됨.
 * - 실시간 손가락 드래그 추종(Interactive Dragging) 및 플링(Fling) 완벽 지원.
 * - 마우스 휠 및 트랙패드 수직 스크롤(ACTION_SCROLL) 애니메이션 지원.
 * - 버튼 클릭 및 수평 슬라이더 조작은 가로채지 않고 온전히 보존.
 */
class SplitMediaContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), NestedScrollingParent3 {

    var onContentTypeChanged: ((newType: String) -> Unit)? = null
    var onPrepareIncomingView: ((incomingType: String) -> Unit)? = null
    var onSwipeVertical: (() -> Unit)? = null

    var activeType: String = "openpilot"
        private set

    private var mediaView: View? = null
    private var opView: View? = null

    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var isAnimating = false
    private var currentAnimator: ValueAnimator? = null
    private var velocityTracker: VelocityTracker? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minSwipeDistance = 30f * context.resources.displayMetrics.density
    private val minFlingVelocity = 450f * context.resources.displayMetrics.density

    init {
        clipChildren = true
        clipToPadding = true
    }

    private fun ensureViews() {
        if (mediaView == null) mediaView = findViewById(R.id.mediaPlayerContainer)
        if (opView == null) opView = findViewById(R.id.openpilotDashboardContainer)
    }

    fun initViews(initialType: String) {
        ensureViews()
        activeType = initialType
        val isOpenpilot = (initialType == "openpilot")

        mediaView?.apply {
            visibility = if (isOpenpilot) View.GONE else View.VISIBLE
            translationY = 0f
            alpha = 1f
        }
        opView?.apply {
            visibility = if (isOpenpilot) View.VISIBLE else View.GONE
            translationY = 0f
            alpha = 1f
        }
        if (isOpenpilot) {
            onPrepareIncomingView?.invoke("openpilot")
        }
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // 자식 뷰가 부모의 수직 스크롤 감지를 방해하지 못하도록 처리
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        ensureViews()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)

                // 진행 중인 애니메이션이 있다면 즉시 정지
                currentAnimator?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = abs(ev.rawX - downX)
                val dy = abs(ev.rawY - downY)
                if (!isDragging && dy > touchSlop && dy > dx * 1.2f) {
                    isDragging = true
                    prepareDragViews()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun prepareDragViews() {
        ensureViews()
        val nextType = if (activeType == "openpilot") "media" else "openpilot"
        val nextView = if (activeType == "openpilot") mediaView else opView
        nextView?.apply {
            visibility = View.VISIBLE
            alpha = 0.7f
        }
        onPrepareIncomingView?.invoke(nextType)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        ensureViews()
        val containerH = if (height > 0) height.toFloat() else 800f
        val currentView = if (activeType == "openpilot") opView else mediaView
        val nextType = if (activeType == "openpilot") "media" else "openpilot"
        val nextView = if (activeType == "openpilot") mediaView else opView

        if (currentView == null || nextView == null) return super.onTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                currentAnimator?.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = abs(ev.rawX - downX)

                if (!isDragging && abs(dy) > touchSlop && abs(dy) > dx * 1.2f) {
                    isDragging = true
                    prepareDragViews()
                }

                if (isDragging) {
                    val clampedDy = dy.coerceIn(-containerH, containerH)
                    currentView.translationY = clampedDy
                    currentView.alpha = (1.0f - 0.35f * (abs(clampedDy) / containerH)).coerceIn(0.65f, 1.0f)

                    nextView.visibility = View.VISIBLE
                    if (clampedDy < 0) {
                        // 위로 드래그 (Swipe UP): 다음 뷰가 아래에서 위로 올라옴
                        nextView.translationY = containerH + clampedDy
                    } else {
                        // 아래로 드래그 (Swipe DOWN): 다음 뷰가 위에서 아래로 내려옴
                        nextView.translationY = -containerH + clampedDy
                    }
                    nextView.alpha = (0.65f + 0.35f * (abs(clampedDy) / containerH)).coerceIn(0.65f, 1.0f)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    velocityTracker?.addMovement(ev)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val yVel = velocityTracker?.yVelocity ?: 0f
                    val clampedDy = (ev.rawY - downY).coerceIn(-containerH, containerH)
                    val absDy = abs(clampedDy)
                    val absVel = abs(yVel)

                    val shouldCommit = absDy >= minSwipeDistance || absVel >= minFlingVelocity
                    if (shouldCommit) {
                        val isUp = if (absVel >= minFlingVelocity) (yVel < 0) else (clampedDy < 0)
                        settleTransition(currentView, nextView, nextType, isUp, containerH)
                    } else {
                        snapBack(currentView, nextView, clampedDy, containerH)
                    }

                    velocityTracker?.recycle()
                    velocityTracker = null
                    isDragging = false
                    return true
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isDragging = false
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun settleTransition(
        currentView: View,
        nextView: View,
        nextType: String,
        isUp: Boolean,
        containerH: Float
    ) {
        currentAnimator?.cancel()
        isAnimating = true

        val startCurrentY = currentView.translationY
        val targetCurrentY = if (isUp) -containerH else containerH
        val startNextY = nextView.translationY
        val targetNextY = 0f

        val startCurrentAlpha = currentView.alpha
        val startNextAlpha = nextView.alpha

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 260L
        animator.interpolator = DecelerateInterpolator(1.6f)
        animator.addUpdateListener { va ->
            val f = va.animatedFraction
            currentView.translationY = startCurrentY + (targetCurrentY - startCurrentY) * f
            currentView.alpha = startCurrentAlpha + (0.65f - startCurrentAlpha) * f

            nextView.translationY = startNextY + (targetNextY - startNextY) * f
            nextView.alpha = startNextAlpha + (1.0f - startNextAlpha) * f
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                currentView.visibility = View.GONE
                currentView.translationY = 0f
                currentView.alpha = 1f

                nextView.translationY = 0f
                nextView.alpha = 1f

                isAnimating = false
                currentAnimator = null
                activeType = nextType

                onContentTypeChanged?.invoke(activeType)
                onSwipeVertical?.invoke()
            }
        })
        currentAnimator = animator
        animator.start()
    }

    private fun snapBack(
        currentView: View,
        nextView: View,
        clampedDy: Float,
        containerH: Float
    ) {
        currentAnimator?.cancel()
        isAnimating = true

        val startCurrentY = currentView.translationY
        val targetCurrentY = 0f
        val startNextY = nextView.translationY
        val targetNextY = if (clampedDy < 0) containerH else -containerH

        val startCurrentAlpha = currentView.alpha
        val startNextAlpha = nextView.alpha

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 200L
        animator.interpolator = DecelerateInterpolator(1.5f)
        animator.addUpdateListener { va ->
            val f = va.animatedFraction
            currentView.translationY = startCurrentY + (targetCurrentY - startCurrentY) * f
            currentView.alpha = startCurrentAlpha + (1.0f - startCurrentAlpha) * f

            nextView.translationY = startNextY + (targetNextY - startNextY) * f
            nextView.alpha = startNextAlpha + (0.65f - startNextAlpha) * f
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                nextView.visibility = View.GONE
                nextView.translationY = 0f
                nextView.alpha = 1f

                currentView.translationY = 0f
                currentView.alpha = 1f

                isAnimating = false
                currentAnimator = null
            }
        })
        currentAnimator = animator
        animator.start()
    }

    /**
     * 외부 호출 및 휠/트랙패드/설정창 연동용 부드러운 스크롤 애니메이션
     */
    fun animateScrollTo(targetType: String, isUp: Boolean = true) {
        ensureViews()
        if (targetType == activeType || isAnimating) return

        val containerH = if (height > 0) height.toFloat() else 800f
        val currentView = if (activeType == "openpilot") opView else mediaView
        val nextView = if (targetType == "openpilot") opView else mediaView

        if (currentView == null || nextView == null) {
            initViews(targetType)
            return
        }

        onPrepareIncomingView?.invoke(targetType)

        currentAnimator?.cancel()
        isAnimating = true

        nextView.visibility = View.VISIBLE
        nextView.translationY = if (isUp) containerH else -containerH
        nextView.alpha = 0.65f

        currentView.translationY = 0f
        currentView.alpha = 1.0f

        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 280L
        animator.interpolator = DecelerateInterpolator(1.6f)
        animator.addUpdateListener { va ->
            val f = va.animatedFraction
            currentView.translationY = (if (isUp) -containerH else containerH) * f
            currentView.alpha = 1.0f - 0.35f * f

            nextView.translationY = (if (isUp) containerH else -containerH) * (1.0f - f)
            nextView.alpha = 0.65f + 0.35f * f
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                currentView.visibility = View.GONE
                currentView.translationY = 0f
                currentView.alpha = 1f

                nextView.translationY = 0f
                nextView.alpha = 1f

                isAnimating = false
                currentAnimator = null
                activeType = targetType

                onContentTypeChanged?.invoke(activeType)
                onSwipeVertical?.invoke()
            }
        })
        currentAnimator = animator
        animator.start()
    }

    fun toggleContent(isUp: Boolean = true) {
        val nextType = if (activeType == "openpilot") "media" else "openpilot"
        animateScrollTo(nextType, isUp)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && !isAnimating) {
            val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (abs(vScroll) > 0.1f) {
                // vScroll < 0: 마우스 휠 아래로 스크롤 (화면 위로 올라감)
                toggleContent(isUp = (vScroll < 0))
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // --- NestedScrollingParent3 Implementation ---

    private var totalDy = 0f

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        return (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        totalDy = 0f
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        if (abs(totalDy) >= minSwipeDistance && !isAnimating) {
            toggleContent(isUp = (totalDy > 0))
        }
        totalDy = 0f
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
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        totalDy += dy.toFloat()
    }
}
