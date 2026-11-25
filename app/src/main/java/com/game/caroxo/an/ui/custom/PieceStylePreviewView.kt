package com.game.caroxo.an.ui.custom

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.game.caroxo.an.model.Piece
import com.game.caroxo.an.model.PieceStyle

class PieceStylePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var pieceStyle: PieceStyle = PieceStyle.CLASSIC
        set(value) {
            if (field != value) {
                field = value
                cachedWidth = 0f
                cachedShader = null
                syncAnimation()
                invalidate()
            }
        }

    private var animPhase: Float = 0f
    private var animator: ValueAnimator? = null
    private var cachedWidth: Float = 0f
    private var cachedShader: Shader? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()

    private fun syncAnimation() {
        val shouldAnimate = pieceStyle.isAnimated && isAttachedToWindow
        if (shouldAnimate) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 2200L
                    interpolator = LinearInterpolator()
                    repeatMode = ValueAnimator.RESTART
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        animPhase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            animator?.cancel()
            animator = null
            animPhase = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        when (pieceStyle) {
            PieceStyle.NEON_CYBER -> {
                bgPaint.shader = null
                bgPaint.color = Color.parseColor("#111827")
            }
            PieceStyle.AD_STORM_VOLT -> {
                if (cachedWidth != w || cachedShader == null) {
                    cachedWidth = w
                    cachedShader = LinearGradient(
                        0f, 0f, w, h,
                        Color.parseColor("#0B192E"),
                        Color.parseColor("#102A43"),
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedShader
            }
            PieceStyle.AD_FROST_CRYSTAL -> {
                if (cachedWidth != w || cachedShader == null) {
                    cachedWidth = w
                    cachedShader = LinearGradient(
                        0f, 0f, w, h,
                        Color.parseColor("#0F233A"),
                        Color.parseColor("#164E63"),
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedShader
            }
            PieceStyle.AD_VOID_SINGULARITY -> {
                if (cachedWidth != w || cachedShader == null) {
                    cachedWidth = w
                    cachedShader = LinearGradient(
                        0f, 0f, w, h,
                        Color.parseColor("#120926"),
                        Color.parseColor("#271147"),
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedShader
            }
            PieceStyle.AD_INK_GOLD -> {
                if (cachedWidth != w || cachedShader == null) {
                    cachedWidth = w
                    cachedShader = LinearGradient(
                        0f, 0f, w, h,
                        Color.parseColor("#F5EFE6"),
                        Color.parseColor("#EADBC8"),
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedShader
            }
            PieceStyle.VIP_CRIMSON_EMBER -> {
                if (cachedWidth != w || cachedShader == null) {
                    cachedWidth = w
                    cachedShader = LinearGradient(
                        0f, 0f, w, h,
                        Color.parseColor("#1F0B10"),
                        Color.parseColor("#331408"),
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedShader
            }
            PieceStyle.VIP_PLASMA_VORTEX -> {
                if (cachedWidth != w || cachedShader == null) {
                    cachedWidth = w
                    cachedShader = LinearGradient(
                        0f, 0f, w, h,
                        Color.parseColor("#0F102B"),
                        Color.parseColor("#0A2538"),
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedShader
            }
            PieceStyle.VIP_CELESTIAL_MONARCH -> {
                if (cachedWidth != w || cachedShader == null) {
                    cachedWidth = w
                    cachedShader = LinearGradient(
                        0f, 0f, w, h,
                        Color.parseColor("#1A1305"),
                        Color.parseColor("#2E1D07"),
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedShader
            }
            else -> {
                bgPaint.shader = null
                bgPaint.color = Color.parseColor("#F1F5F9")
            }
        }
        canvas.drawRoundRect(0f, 0f, w, h, 24f, 24f, bgPaint)

        val cellSize = minOf(w / 2.2f, h * 0.88f)
        CaroBoardView.drawStyledPiece(
            canvas = canvas,
            piece = Piece.X,
            pieceStyle = pieceStyle,
            cx = w * 0.30f,
            cy = h * 0.50f,
            cellSize = cellSize,
            scale = 1.0f,
            alpha = 255,
            strokePaint = strokePaint,
            fillPaint = fillPaint,
            path = path,
            animPhase = animPhase
        )
        CaroBoardView.drawStyledPiece(
            canvas = canvas,
            piece = Piece.O,
            pieceStyle = pieceStyle,
            cx = w * 0.70f,
            cy = h * 0.50f,
            cellSize = cellSize,
            scale = 1.0f,
            alpha = 255,
            strokePaint = strokePaint,
            fillPaint = fillPaint,
            path = path,
            animPhase = animPhase
        )
    }
}
