package com.agy.rewardsnode.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.roundToInt

class ActivityRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val COLOR_PC = 0xFF10B981.toInt()       // #10B981 Emerald
        private const val COLOR_EDGE = 0xFFF59E0B.toInt()     // #F59E0B Amber
        private const val COLOR_MOBILE = 0xFF06B6D4.toInt()   // #06B6D4 Cyan
        private const val TRACK_ALPHA_RATIO = 0.15f
        private const val START_ANGLE = -90f
        private const val ANIMATION_DURATION_MS = 800L
    }

    private val outerStrokeWidth = dpToPx(11f)
    private val middleStrokeWidth = dpToPx(9f)
    private val innerStrokeWidth = dpToPx(7f)
    private val ringGap = dpToPx(3f)

    private val outerRect = RectF()
    private val middleRect = RectF()
    private val innerRect = RectF()

    private val outerTrackPaint = createPaint(COLOR_PC, TRACK_ALPHA_RATIO, outerStrokeWidth)
    private val outerProgressPaint = createPaint(COLOR_PC, 1.0f, outerStrokeWidth)

    private val middleTrackPaint = createPaint(COLOR_EDGE, TRACK_ALPHA_RATIO, middleStrokeWidth)
    private val middleProgressPaint = createPaint(COLOR_EDGE, 1.0f, middleStrokeWidth)

    private val innerTrackPaint = createPaint(COLOR_MOBILE, TRACK_ALPHA_RATIO, innerStrokeWidth)
    private val innerProgressPaint = createPaint(COLOR_MOBILE, 1.0f, innerStrokeWidth)

    private var currentPcRatio = 0f
    private var currentEdgeRatio = 0f
    private var currentMobileRatio = 0f

    private var animator: ValueAnimator? = null

    private fun createPaint(color: Int, alphaRatio: Float, strokeWidth: Float): Paint {
        val alpha = (255 * alphaRatio).roundToInt().coerceIn(0, 255)
        val finalColor = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            this.color = finalColor
            strokeCap = Paint.Cap.ROUND
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }

    private fun sanitizeRatio(ratio: Float): Float {
        if (ratio.isNaN() || ratio.isInfinite() || ratio <= 0f) return 0f
        return ratio.coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        val contentWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        val diameter = min(contentWidth, contentHeight).toFloat()
        if (diameter <= 0f) return

        val cx = paddingLeft + contentWidth / 2f
        val cy = paddingTop + contentHeight / 2f

        val outerRadius = (diameter - outerStrokeWidth) / 2f
        if (outerRadius <= 0f) return
        outerRect.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)

        val middleRadius = outerRadius - outerStrokeWidth / 2f - ringGap - middleStrokeWidth / 2f
        if (middleRadius > 0f) {
            middleRect.set(cx - middleRadius, cy - middleRadius, cx + middleRadius, cy + middleRadius)
        } else {
            middleRect.setEmpty()
        }

        val innerRadius = middleRadius - middleStrokeWidth / 2f - ringGap - innerStrokeWidth / 2f
        if (innerRadius > 0f) {
            innerRect.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
        } else {
            innerRect.setEmpty()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Outer ring: Track & Progress
        if (!outerRect.isEmpty) {
            canvas.drawArc(outerRect, START_ANGLE, 360f, false, outerTrackPaint)
            if (currentPcRatio > 0f) {
                canvas.drawArc(outerRect, START_ANGLE, currentPcRatio * 360f, false, outerProgressPaint)
            }
        }

        // Middle ring: Track & Progress
        if (!middleRect.isEmpty) {
            canvas.drawArc(middleRect, START_ANGLE, 360f, false, middleTrackPaint)
            if (currentEdgeRatio > 0f) {
                canvas.drawArc(middleRect, START_ANGLE, currentEdgeRatio * 360f, false, middleProgressPaint)
            }
        }

        // Inner ring: Track & Progress
        if (!innerRect.isEmpty) {
            canvas.drawArc(innerRect, START_ANGLE, 360f, false, innerTrackPaint)
            if (currentMobileRatio > 0f) {
                canvas.drawArc(innerRect, START_ANGLE, currentMobileRatio * 360f, false, innerProgressPaint)
            }
        }
    }

    fun setProgress(pcRatio: Float, edgeRatio: Float, mobileRatio: Float, animate: Boolean = true) {
        val targetPc = sanitizeRatio(pcRatio)
        val targetEdge = sanitizeRatio(edgeRatio)
        val targetMobile = sanitizeRatio(mobileRatio)

        animator?.cancel()

        if (!animate) {
            currentPcRatio = targetPc
            currentEdgeRatio = targetEdge
            currentMobileRatio = targetMobile
            invalidate()
            return
        }

        val startPc = currentPcRatio
        val startEdge = currentEdgeRatio
        val startMobile = currentMobileRatio

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                currentPcRatio = startPc + (targetPc - startPc) * fraction
                currentEdgeRatio = startEdge + (targetEdge - startEdge) * fraction
                currentMobileRatio = startMobile + (targetMobile - startMobile) * fraction
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }
}
