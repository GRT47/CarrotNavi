package com.example.carrotnavi

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.example.carrotnavi.databinding.LayoutHudOverlaysBinding
import kotlin.math.max

class HudOverlayManager(
    private val activity: Activity,
    val binding: LayoutHudOverlaysBinding,
    private val lifecycleOwner: LifecycleOwner
) {
    private val sharedPref: SharedPreferences = activity.getSharedPreferences("CarrotNaviPrefs", Context.MODE_PRIVATE)
    private var isEditMode = false
    private var isOverlayVisible = true

    private var initialX = 0f
    private var initialY = 0f
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Pinch-to-zoom
    private var initialDistance = 0f
    private var initialScale = 1f
    private var isScaling = false

    init {
                setupUI()
        setupObservers()
    }

    fun onDestroy() {
            }

    private fun setupUI() {
        val isLandscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val draggables = listOfNotNull(
            binding.llSpeedGroup,
            binding.llBottomLeftOverlays,
            binding.llTopUiGroup,
            binding.llBlockInfo,
            binding.llStatusGroup
        )

        draggables.forEach { view ->
            view.post {
                val others = draggables.filter { it != view }
                val viewIdName = activity.resources.getResourceEntryName(view.id)
                restorePosition(view, viewIdName, isLandscape, others)
            }
        }

        draggables.forEach { view ->
            val others = draggables.filter { it != view }
            val viewIdName = activity.resources.getResourceEntryName(view.id)
            makeDraggable(view, viewIdName, isLandscape, others)
        }

        isOverlayVisible = sharedPref.getBoolean("OVERLAY_VISIBLE", true)
        val isDebugOverlayVisible = sharedPref.getBoolean("DEBUG_OVERLAY_VISIBLE", false)
        updateOverlayVisibility()

        
        
        binding.btnToggleVisibility.setOnClickListener {
            isOverlayVisible = !isOverlayVisible
            sharedPref.edit().putBoolean("OVERLAY_VISIBLE", isOverlayVisible).apply()
            updateOverlayVisibility()
        }

        binding.btnSettings?.setOnClickListener {
            val intent = android.content.Intent(activity, MainActivity::class.java)
            intent.putExtra("auto_start", false)
            activity.startActivity(intent)
            activity.finish()
        }

        binding.btnEditMode.setOnClickListener {
            isEditMode = !isEditMode
            updateEditModeForegrounds()
            if (isEditMode) {
                binding.btnEditMode.setBackgroundResource(R.drawable.shape_circle_green)
                binding.btnRestoreDefaults.visibility = View.VISIBLE
                Toast.makeText(activity, "오버레이 편집 모드 켜짐", Toast.LENGTH_SHORT).show()
            } else {
                binding.btnEditMode.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.btnRestoreDefaults.visibility = View.GONE
                Toast.makeText(activity, "오버레이 편집 모드 꺼짐", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRestoreDefaults.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle("오버레이 배치 초기화")
                .setMessage("모든 위젯의 배치를 기본값으로 복원하시겠습니까?")
                .setPositiveButton("복원") { _, _ ->
                    sharedPref.edit()
                        .remove("llSpeedGroup_x_port")
                        .remove("llSpeedGroup_y_port")
                        .remove("llSpeedGroup_x_land")
                        .remove("llSpeedGroup_y_land")
                        .remove("llBottomLeftOverlays_x_port")
                        .remove("llBottomLeftOverlays_y_port")
                        .remove("llBottomLeftOverlays_x_land")
                        .remove("llBottomLeftOverlays_y_land")
                        .remove("llSpeedGroup_scale_port")
                        .remove("llSpeedGroup_scale_land")
                        .remove("llBottomLeftOverlays_scale_port")
                        .remove("llBottomLeftOverlays_scale_land")
                        .remove("llStatusGroup_x_port")
                        .remove("llStatusGroup_y_port")
                        .remove("llStatusGroup_x_land")
                        .remove("llStatusGroup_y_land")
                        .remove("llStatusGroup_scale_port")
                        .remove("llStatusGroup_scale_land")
                        .apply()

                    // Reset views to layout defaults immediately
                    binding.llSpeedGroup.translationX = 0f
                    binding.llSpeedGroup.translationY = 0f
                    binding.llBottomLeftOverlays.translationX = 0f
                    binding.llBottomLeftOverlays.translationY = 0f
                    binding.llStatusGroup?.translationX = 0f
                    binding.llStatusGroup?.translationY = 0f

                    binding.llSpeedGroup.scaleX = 1f
                    binding.llSpeedGroup.scaleY = 1f
                    binding.llBottomLeftOverlays.scaleX = 1f
                    binding.llBottomLeftOverlays.scaleY = 1f
                    binding.llStatusGroup?.scaleX = 1f
                    binding.llStatusGroup?.scaleY = 1f

                    Toast.makeText(activity, "기본값으로 복원되었습니다.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun setAutoRepeatButton(button: Button, action: () -> Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var runnable: Runnable? = null

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isEditMode) return@setOnTouchListener false
                    action()
                    runnable = object : Runnable {
                        override fun run() {
                            action()
                            handler.postDelayed(this, 100)
                        }
                    }
                    handler.postDelayed(runnable!!, 500)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    runnable?.let { handler.removeCallbacks(it) }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateOverlayVisibility() {
        val visibility = if (isOverlayVisible) View.VISIBLE else View.GONE
        binding.llSpeedGroup.visibility = visibility
        binding.llBottomLeftOverlays.visibility = visibility
        binding.llTopUiGroup?.visibility = visibility
        binding.llBlockInfo?.visibility = visibility
        binding.llStatusGroup?.visibility = visibility
        
        binding.btnToggleVisibility.alpha = if (isOverlayVisible) 1.0f else 0.5f
    }

    private fun updateEditModeForegrounds() {
        if (isEditMode) {
            binding.llSpeedGroup.foreground = HatchedDrawable(binding.llSpeedGroup)
            binding.llBottomLeftOverlays.foreground = HatchedDrawable(binding.llBottomLeftOverlays)
            binding.llTopUiGroup?.let { it.foreground = HatchedDrawable(it) }
            binding.llBlockInfo?.let { it.foreground = HatchedDrawable(it) }
            binding.llStatusGroup?.let { it.foreground = HatchedDrawable(it) }
        } else {
            binding.llSpeedGroup.foreground = null
            binding.llBottomLeftOverlays.foreground = null
            binding.llTopUiGroup?.foreground = null
            binding.llBlockInfo?.foreground = null
            binding.llStatusGroup?.foreground = null
        }
    }

    private fun setupObservers() {
        OpenpilotStateRepository.state.observe(lifecycleOwner, Observer { state ->
            binding.tvCarrotVersion?.text = "Ver: ${state.carrot2}"
            binding.tvCarrotIp?.text = "IP: ${state.ip}"
            
            if (state.ip != "-" && state.ip.isNotEmpty()) {
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_green)
                binding.tvConnectionStatus?.text = "OP 연결됨"
                binding.tvConnectionStatus?.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                binding.vConnectionDot?.setBackgroundResource(R.drawable.shape_circle_gray)
                binding.tvConnectionStatus?.text = "OP 연결 대기"
                binding.tvConnectionStatus?.setTextColor(Color.parseColor("#AAAAAA"))
            }


            // they are in the Top Bar which is still in Activity's XML!
            // Wait, I should not update TopBar views here if they are not in binding.
        })
    }

    private fun makeDraggable(view: View, viewIdName: String, isLandscape: Boolean, otherViews: List<View>) {
        view.setOnTouchListener { v, event ->
            if (!isEditMode) return@setOnTouchListener false

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = view.translationX
                    initialY = view.translationY
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isScaling = false
                    view.bringToFront()
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isScaling = true
                        initialDistance = getDistance(event)
                        initialScale = view.scaleX
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isScaling && event.pointerCount == 2) {
                        val currentDistance = getDistance(event)
                        if (initialDistance > 0) {
                            val scaleFactor = currentDistance / initialDistance
                            val newScale = max(0.5f, initialScale * scaleFactor)
                            view.scaleX = newScale
                            view.scaleY = newScale
                        }
                    } else if (!isScaling) {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY

                        var newTranslationX = initialX + dx
                        var newTranslationY = initialY + dy

                        val parent = view.parent as? ViewGroup
                        if (parent != null) {
                            val scaledWidth = view.width * view.scaleX
                            val scaledHeight = view.height * view.scaleY
                            val widthDiff = (scaledWidth - view.width) / 2
                            val heightDiff = (scaledHeight - view.height) / 2

                            val minX = -view.left.toFloat() + widthDiff
                            val maxX = parent.width - view.right.toFloat() - widthDiff
                            val minY = -view.top.toFloat() + heightDiff
                            val maxY = parent.height - view.bottom.toFloat() - heightDiff

                            newTranslationX = newTranslationX.coerceIn(minX, maxX)
                            newTranslationY = newTranslationY.coerceIn(minY, maxY)

                            // Snapping logic
                            val snapDistance = 40f
                            var snappedX = false
                            var snappedY = false

                            val viewCenterX = view.left + newTranslationX + view.width / 2
                            val viewCenterY = view.top + newTranslationY + view.height / 2

                            for (other in otherViews) {
                                val otherCenterX = other.left + other.translationX + other.width / 2
                                val otherCenterY = other.top + other.translationY + other.height / 2

                                if (Math.abs(viewCenterX - otherCenterX) < snapDistance) {
                                    newTranslationX = otherCenterX - view.width / 2 - view.left
                                    snappedX = true
                                }
                                if (Math.abs(viewCenterY - otherCenterY) < snapDistance) {
                                    newTranslationY = otherCenterY - view.height / 2 - view.top
                                    snappedY = true
                                }
                            }

                            val parentCenterX = parent.width / 2f
                            val parentCenterY = parent.height / 2f
                            if (!snappedX && Math.abs(viewCenterX - parentCenterX) < snapDistance) {
                                newTranslationX = parentCenterX - view.width / 2 - view.left
                            }
                            if (!snappedY && Math.abs(viewCenterY - parentCenterY) < snapDistance) {
                                newTranslationY = parentCenterY - view.height / 2 - view.top
                            }
                        }

                        view.translationX = newTranslationX
                        view.translationY = newTranslationY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        isScaling = false
                        val keyPrefix = if (isLandscape) "${viewIdName}_land" else "${viewIdName}_port"
                        sharedPref.edit()
                            .putFloat("${keyPrefix}_x", view.translationX)
                            .putFloat("${keyPrefix}_y", view.translationY)
                            .putFloat("${keyPrefix}_scale", view.scaleX)
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun restorePosition(view: View, viewIdName: String, isLandscape: Boolean, otherViews: List<View>) {
        val keyPrefix = if (isLandscape) "${viewIdName}_land" else "${viewIdName}_port"
        val savedX = sharedPref.getFloat("${keyPrefix}_x", 0f)
        val savedY = sharedPref.getFloat("${keyPrefix}_y", 0f)
        val savedScale = sharedPref.getFloat("${keyPrefix}_scale", 1f)

        view.scaleX = savedScale
        view.scaleY = savedScale
        view.translationX = savedX
        view.translationY = savedY

        // Boundaries check
        val parent = view.parent as? ViewGroup
        if (parent != null && parent.width > 0 && parent.height > 0) {
            val scaledWidth = view.width * view.scaleX
            val scaledHeight = view.height * view.scaleY
            val widthDiff = (scaledWidth - view.width) / 2
            val heightDiff = (scaledHeight - view.height) / 2

            val minX = -view.left.toFloat() + widthDiff
            val maxX = parent.width - view.right.toFloat() - widthDiff
            val minY = -view.top.toFloat() + heightDiff
            val maxY = parent.height - view.bottom.toFloat() - heightDiff

            view.translationX = view.translationX.coerceIn(minX, maxX)
            view.translationY = view.translationY.coerceIn(minY, maxY)
        }
    }

    private fun getDistance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isEditMode) {
            val touchedOverlay = findTouchedOverlay(ev)
            if (touchedOverlay != null) {
                touchedOverlay.dispatchTouchEvent(ev)
                return true // Consume in edit mode
            }
            return true // Block map interactions in edit mode
        }
        return false // Return false to indicate unhandled by manager (Activity handles it)
    }

    private fun findTouchedOverlay(ev: MotionEvent): View? {
        val x = ev.rawX.toInt()
        val y = ev.rawY.toInt()
        val overlays = listOfNotNull(
            binding.llSpeedGroup,
            binding.llBottomLeftOverlays,
            binding.llTopUiGroup,
            binding.llBlockInfo,
            binding.llStatusGroup
        )

        val location = IntArray(2)
        for (overlay in overlays) {
            if (overlay.visibility == View.VISIBLE) {
                overlay.getLocationOnScreen(location)
                val left = location[0]
                val top = location[1]
                val right = left + (overlay.width * overlay.scaleX).toInt()
                val bottom = top + (overlay.height * overlay.scaleY).toInt()

                if (x in left..right && y in top..bottom) {
                    return overlay
                }
            }
        }
        return null
    }

    private class HatchedDrawable(private val targetView: View, baseColor: Int = Color.parseColor("#33FFC107")) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FFC107")
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }
        private val bgPaint = Paint().apply {
            color = baseColor
            style = Paint.Style.FILL
        }

        override fun draw(canvas: android.graphics.Canvas) {
            val bounds = bounds
            canvas.drawRect(bounds, bgPaint)
            val size = max(bounds.width(), bounds.height()) * 2
            val spacing = 20
            for (i in -size..size step spacing) {
                canvas.drawLine(
                    bounds.left.toFloat() + i, bounds.top.toFloat(),
                    bounds.left.toFloat() + i + size, bounds.top.toFloat() + size,
                    paint
                )
            }
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}
