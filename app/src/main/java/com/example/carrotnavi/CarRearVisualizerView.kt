package com.example.carrotnavi

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * 오픈파일럿 텔레메트리 연동 차량 후방 뷰 (Car Rear Visualizer)
 * - 좌/우 방향지시등 (앰버 옐로우 발광)
 * - 제동등 (테일램프 및 상단 보조제동등 CHMSL 크림슨 레드 발광)
 * - 상시 미등(Tail lamp) 슬림 LED 바
 * - 모던 EV / 스포츠 세단 와이드 스탠스 벡터 렌더링
 */
class CarRearVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var leftBlinker: Boolean = false
    private var rightBlinker: Boolean = false
    private var brakeLights: Boolean = false

    // Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Color Palette
    private val colorRoadLine = Color.parseColor("#1E2538")
    private val colorTire = Color.parseColor("#18181B")
    private val colorCarBodyDark = Color.parseColor("#1C1F26")
    private val colorCarBodyLight = Color.parseColor("#282D37")
    private val colorCarStroke = Color.parseColor("#383E4C")
    private val colorGlass = Color.parseColor("#12161F")
    private val colorGlassHighlight = Color.parseColor("#2A3142")
    private val colorDiffuser = Color.parseColor("#111317")

    // Lighting Colors
    private val colorTailNormal = Color.parseColor("#661818")
    private val colorTailNormalStroke = Color.parseColor("#882020")
    private val colorBrakeActive = Color.parseColor("#FF1722")
    private val colorBrakeGlow = Color.parseColor("#66FF1722")
    private val colorBlinkerActive = Color.parseColor("#FFB800")
    private val colorBlinkerGlow = Color.parseColor("#66FFB800")
    private val colorChmslNormal = Color.parseColor("#331010")

    init {
        // 하드웨어 가속 최적화
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setVehicleLights(leftBlinker: Boolean, rightBlinker: Boolean, brakeLights: Boolean) {
        if (this.leftBlinker != leftBlinker || this.rightBlinker != rightBlinker || this.brakeLights != brakeLights) {
            this.leftBlinker = leftBlinker
            this.rightBlinker = rightBlinker
            this.brakeLights = brakeLights
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val carW = Math.min(w * 0.76f, h * 2.2f)
        val carH = carW * 0.44f
        val bottomY = h * 0.82f
        val topY = bottomY - carH
        val halfW = carW / 2f

        // 1. 노면 및 차선 원근감 가이드라인
        drawRoad(canvas, cx, bottomY, w, h)

        // 2. 바닥 그림자
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.parseColor("#6605070B")
        val shadowRect = RectF(cx - halfW * 1.05f, bottomY - carH * 0.1f, cx + halfW * 1.05f, bottomY + carH * 0.15f)
        canvas.drawOval(shadowRect, paint)

        // 3. 브레이크 작동 시 노면 반사광
        if (brakeLights) {
            paint.color = Color.parseColor("#25FF1722")
            val brakeReflectionRect = RectF(cx - halfW * 0.9f, bottomY - carH * 0.05f, cx + halfW * 0.9f, bottomY + carH * 0.22f)
            canvas.drawOval(brakeReflectionRect, paint)
        }

        // 4. 후륜 타이어 (와이드 스탠스)
        val tireW = carW * 0.12f
        val tireH = carH * 0.36f
        paint.color = colorTire
        paint.style = Paint.Style.FILL
        // 좌측 타이어
        canvas.drawRoundRect(cx - halfW, bottomY - tireH * 0.85f, cx - halfW + tireW, bottomY + tireH * 0.15f, 6f, 6f, paint)
        // 우측 타이어
        canvas.drawRoundRect(cx + halfW - tireW, bottomY - tireH * 0.85f, cx + halfW, bottomY + tireH * 0.15f, 6f, 6f, paint)

        // 5. 차체 본체 (Lower & Upper Body)
        drawCarBody(canvas, cx, topY, bottomY, halfW, carH)

        // 6. 리어 윈도우 (Dark Glass)
        drawRearGlass(canvas, cx, topY, carH, halfW)

        // 7. 보조 제동등 (CHMSL, 리어 글래스 상단)
        drawChmsl(canvas, cx, topY + carH * 0.06f, carW * 0.22f, carH * 0.045f)

        // 8. 테일램프 및 방향지시등 / 제동등 (Full-width LED Bar + End Clusters)
        drawTailLights(canvas, cx, topY + carH * 0.58f, halfW, carH)

        // 9. 번호판 및 하단 디퓨저 라인
        drawRearDetails(canvas, cx, topY, bottomY, halfW, carH)
    }

    private fun drawRoad(canvas: Canvas, cx: Float, bottomY: Float, w: Float, h: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.color = colorRoadLine
        paint.strokeWidth = 2.5f
        paint.style = Paint.Style.STROKE

        // 좌/우 차선 원근 라인
        canvas.drawLine(cx - w * 0.22f, bottomY - h * 0.35f, cx - w * 0.44f, h, paint)
        canvas.drawLine(cx + w * 0.22f, bottomY - h * 0.35f, cx + w * 0.44f, h, paint)
    }

    private fun drawCarBody(canvas: Canvas, cx: Float, topY: Float, bottomY: Float, halfW: Float, carH: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL

        // 차체 외곽 패스 (루프 -> 숄더 -> 휠아치 -> 범퍼 하단)
        path.reset()
        val roofHalfW = halfW * 0.52f
        val shoulderHalfW = halfW * 0.94f
        val bodyHalfW = halfW * 0.98f

        path.moveTo(cx - roofHalfW, topY + carH * 0.04f)
        // 루프탑 곡선
        path.quadTo(cx, topY, cx + roofHalfW, topY + carH * 0.04f)
        // C필러 및 리어 윈도우 프레임
        path.lineTo(cx + shoulderHalfW, topY + carH * 0.48f)
        // 숄더 라인 및 리어 펜더 볼륨
        path.lineTo(cx + bodyHalfW, topY + carH * 0.72f)
        // 범퍼 사이드
        path.lineTo(cx + bodyHalfW * 0.96f, bottomY - carH * 0.04f)
        // 범퍼 하단 디퓨저 라인
        path.quadTo(cx, bottomY + carH * 0.02f, cx - bodyHalfW * 0.96f, bottomY - carH * 0.04f)
        path.lineTo(cx - bodyHalfW, topY + carH * 0.72f)
        path.lineTo(cx - shoulderHalfW, topY + carH * 0.48f)
        path.close()

        // 바디 그라디언트 채우기
        paint.shader = LinearGradient(
            cx, topY, cx, bottomY,
            colorCarBodyLight, colorCarBodyDark,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, paint)

        // 바디 외곽선 (미려한 테두리)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = colorCarStroke
        paint.strokeWidth = 1.5f
        canvas.drawPath(path, paint)
    }

    private fun drawRearGlass(canvas: Canvas, cx: Float, topY: Float, carH: Float, halfW: Float) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL

        val glassPath = Path()
        val glassTopHalfW = halfW * 0.46f
        val glassBottomHalfW = halfW * 0.78f
        val glassTopY = topY + carH * 0.06f
        val glassBottomY = topY + carH * 0.48f

        glassPath.moveTo(cx - glassTopHalfW, glassTopY)
        glassPath.lineTo(cx + glassTopHalfW, glassTopY)
        glassPath.lineTo(cx + glassBottomHalfW, glassBottomY)
        glassPath.lineTo(cx - glassBottomHalfW, glassBottomY)
        glassPath.close()

        // 틴티드 글래스 그라디언트
        paint.shader = LinearGradient(
            cx, glassTopY, cx, glassBottomY,
            colorGlassHighlight, colorGlass,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(glassPath, paint)

        // 글래스 테두리
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#222834")
        paint.strokeWidth = 1.2f
        canvas.drawPath(glassPath, paint)
    }

    private fun drawChmsl(canvas: Canvas, cx: Float, y: Float, width: Float, height: Float) {
        paint.reset()
        paint.isAntiAlias = true

        val halfW = width / 2f
        val rect = RectF(cx - halfW, y, cx + halfW, y + height)

        if (brakeLights) {
            // 글로우 효과
            paint.style = Paint.Style.FILL
            paint.color = colorBrakeGlow
            val glowRect = RectF(cx - halfW * 1.2f, y - height * 0.6f, cx + halfW * 1.2f, y + height * 1.6f)
            canvas.drawRoundRect(glowRect, 4f, 4f, paint)

            // 점등 램프
            paint.color = colorBrakeActive
            canvas.drawRoundRect(rect, 3f, 3f, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = colorChmslNormal
            canvas.drawRoundRect(rect, 2f, 2f, paint)
        }
    }

    private fun drawTailLights(canvas: Canvas, cx: Float, y: Float, halfW: Float, carH: Float) {
        val lightBarHalfW = halfW * 0.88f
        val clusterW = halfW * 0.26f
        val barH = carH * 0.06f

        // --- 1. 중앙 연결형 라이트 바 (Center Light Bar) ---
        paint.reset()
        paint.isAntiAlias = true
        val centerBarRect = RectF(cx - lightBarHalfW + clusterW * 0.8f, y, cx + lightBarHalfW - clusterW * 0.8f, y + barH * 0.6f)

        if (brakeLights) {
            paint.color = colorBrakeGlow
            paint.style = Paint.Style.FILL
            val glowRect = RectF(centerBarRect.left - 4f, centerBarRect.top - 3f, centerBarRect.right + 4f, centerBarRect.bottom + 3f)
            canvas.drawRoundRect(glowRect, 3f, 3f, paint)

            paint.color = colorBrakeActive
            canvas.drawRoundRect(centerBarRect, 2f, 2f, paint)
        } else {
            paint.color = colorTailNormal
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(centerBarRect, 2f, 2f, paint)
        }

        // --- 2. 좌측 테일램프 클러스터 ---
        val leftClusterLeft = cx - lightBarHalfW
        val leftClusterRight = leftClusterLeft + clusterW
        drawSideCluster(
            canvas = canvas,
            left = leftClusterLeft,
            top = y - barH * 0.4f,
            right = leftClusterRight,
            bottom = y + barH * 1.4f,
            isBlinkerActive = leftBlinker,
            isBrakeActive = brakeLights,
            isLeftSide = true
        )

        // --- 3. 우측 테일램프 클러스터 ---
        val rightClusterRight = cx + lightBarHalfW
        val rightClusterLeft = rightClusterRight - clusterW
        drawSideCluster(
            canvas = canvas,
            left = rightClusterLeft,
            top = y - barH * 0.4f,
            right = rightClusterRight,
            bottom = y + barH * 1.4f,
            isBlinkerActive = rightBlinker,
            isBrakeActive = brakeLights,
            isLeftSide = false
        )
    }

    private fun drawSideCluster(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        isBlinkerActive: Boolean,
        isBrakeActive: Boolean,
        isLeftSide: Boolean
    ) {
        val clusterW = right - left
        val clusterH = bottom - top
        val midX = if (isLeftSide) left + clusterW * 0.45f else left + clusterW * 0.55f

        // 클러스터 하우징 패스 (날렵한 C-Shape / Blade 디자인)
        val housingPath = Path()
        if (isLeftSide) {
            housingPath.moveTo(right, top + clusterH * 0.2f)
            housingPath.lineTo(left + clusterW * 0.2f, top)
            housingPath.lineTo(left, top + clusterH * 0.35f)
            housingPath.lineTo(left, bottom - clusterH * 0.25f)
            housingPath.lineTo(left + clusterW * 0.3f, bottom)
            housingPath.lineTo(right, bottom - clusterH * 0.2f)
        } else {
            housingPath.moveTo(left, top + clusterH * 0.2f)
            housingPath.lineTo(right - clusterW * 0.2f, top)
            housingPath.lineTo(right, top + clusterH * 0.35f)
            housingPath.lineTo(right, bottom - clusterH * 0.25f)
            housingPath.lineTo(right - clusterW * 0.3f, bottom)
            housingPath.lineTo(left, bottom - clusterH * 0.2f)
        }
        housingPath.close()

        // 1. 외측: 방향지시등 영역 (Blinker Zone)
        val blinkerRect = if (isLeftSide) {
            RectF(left - 2f, top, midX, bottom)
        } else {
            RectF(midX, top, right + 2f, bottom)
        }

        if (isBlinkerActive) {
            // 앰버 글로우 효과
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = colorBlinkerGlow
            val glowRect = RectF(blinkerRect.left - 6f, blinkerRect.top - 4f, blinkerRect.right + 6f, blinkerRect.bottom + 4f)
            canvas.drawRoundRect(glowRect, 8f, 8f, paint)

            // 선명한 앰버 램프
            paint.color = colorBlinkerActive
            canvas.drawRoundRect(blinkerRect, 4f, 4f, paint)
        } else {
            // 비활성 시 어두운 앰버/다크
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#332410")
            canvas.drawRoundRect(blinkerRect, 3f, 3f, paint)
        }

        // 2. 내측/메인: 제동등 및 미등 영역 (Brake / Tail Zone)
        val brakeRect = if (isLeftSide) {
            RectF(midX, top + clusterH * 0.15f, right, bottom - clusterH * 0.15f)
        } else {
            RectF(left, top + clusterH * 0.15f, midX, bottom - clusterH * 0.15f)
        }

        paint.reset()
        paint.isAntiAlias = true
        if (isBrakeActive) {
            // 브레이크 강렬한 레드 글로우
            paint.style = Paint.Style.FILL
            paint.color = colorBrakeGlow
            val glowRect = RectF(brakeRect.left - 6f, brakeRect.top - 5f, brakeRect.right + 6f, brakeRect.bottom + 5f)
            canvas.drawRoundRect(glowRect, 8f, 8f, paint)

            paint.color = colorBrakeActive
            canvas.drawRoundRect(brakeRect, 3f, 3f, paint)
        } else {
            // 평상시 은은한 미등
            paint.style = Paint.Style.FILL
            paint.color = colorTailNormal
            canvas.drawRoundRect(brakeRect, 2f, 2f, paint)
        }

        // 3. 클러스터 외곽 슬림 라인
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = if (isBrakeActive) colorBrakeActive else if (isBlinkerActive) colorBlinkerActive else colorTailNormalStroke
        canvas.drawPath(housingPath, paint)
    }

    private fun drawRearDetails(canvas: Canvas, cx: Float, topY: Float, bottomY: Float, halfW: Float, carH: Float) {
        paint.reset()
        paint.isAntiAlias = true

        // 1. 번호판 플레이트 (License Plate Recess)
        val plateW = carH * 0.52f
        val plateH = carH * 0.16f
        val plateY = topY + carH * 0.69f
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#121419")
        val plateRect = RectF(cx - plateW / 2f, plateY, cx + plateW / 2f, plateY + plateH)
        canvas.drawRoundRect(plateRect, 3f, 3f, paint)

        // 번호판 프레임 라인
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#2B303C")
        paint.strokeWidth = 1f
        canvas.drawRoundRect(plateRect, 3f, 3f, paint)

        // 2. 하단 리어 디퓨저 및 반사판 (Diffuser Fins & Reflectors)
        val diffuserY = bottomY - carH * 0.08f
        paint.style = Paint.Style.FILL
        paint.color = colorDiffuser
        val diffuserRect = RectF(cx - halfW * 0.45f, diffuserY, cx + halfW * 0.45f, bottomY)
        canvas.drawRoundRect(diffuserRect, 2f, 2f, paint)

        // 디퓨저 핀 3개
        paint.color = Color.parseColor("#1F232B")
        val finW = 2.5f
        val finH = carH * 0.07f
        canvas.drawRect(cx - halfW * 0.18f - finW / 2f, diffuserY + 2f, cx - halfW * 0.18f + finW / 2f, diffuserY + finH, paint)
        canvas.drawRect(cx - finW / 2f, diffuserY + 2f, cx + finW / 2f, diffuserY + finH, paint)
        canvas.drawRect(cx + halfW * 0.18f - finW / 2f, diffuserY + 2f, cx + halfW * 0.18f + finW / 2f, diffuserY + finH, paint)

        // 좌/우 슬림 리플렉터 (슬림 레드 라인)
        paint.color = Color.parseColor("#661818")
        val refW = halfW * 0.16f
        val refH = 2.5f
        val refY = bottomY - carH * 0.07f
        canvas.drawRoundRect(cx - halfW * 0.78f, refY, cx - halfW * 0.78f + refW, refY + refH, 1f, 1f, paint)
        canvas.drawRoundRect(cx + halfW * 0.78f - refW, refY, cx + halfW * 0.78f, refY + refH, 1f, 1f, paint)
    }
}
