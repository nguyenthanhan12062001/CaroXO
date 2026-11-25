package com.game.caroxo.an.ui.custom

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.game.caroxo.an.model.BoardPoint
import com.game.caroxo.an.model.BoardSurface
import com.game.caroxo.an.model.Piece
import com.game.caroxo.an.model.PieceStyle
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class CaroBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onCellTapped: ((row: Int, col: Int) -> Unit)? = null
    var onZoomChanged: ((scale: Float) -> Unit)? = null

    private var boardSize: Int = 15
    private var board: Array<IntArray> = Array(15) { IntArray(15) }
    private var lastMove: BoardPoint? = null
    private var hintPoint: BoardPoint? = null
    private var winLine: List<BoardPoint> = emptyList()
    private var activeTurnPiece: Piece = Piece.X
    private var interactionEnabled: Boolean = true

    private var xPieceStyle: PieceStyle = PieceStyle.CLASSIC
    private var oPieceStyle: PieceStyle = PieceStyle.CLASSIC
    private var boardSurface: BoardSurface = BoardSurface.MINIMAL_CREAM

    // Zoom & Pan state
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private val minScale = 1.0f
    private val maxScale = 3.0f

    // Touch & Ghost Preview state
    private var ghostPoint: BoardPoint? = null
    private var downX = 0f
    private var downY = 0f
    private var isPanningOrZooming = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Animations
    private var newestAnimatedPoint: BoardPoint? = null
    private var pieceAnimProgress = 1.0f
    private var pieceAnimator: ValueAnimator? = null

    private var winLineProgress = 1.0f
    private var winAnimator: ValueAnimator? = null

    private var hintPulseFraction = 0.5f
    private var hintAnimator: ValueAnimator? = null

    private var vipAnimPhase = 0f
    private var vipAnimator: ValueAnimator? = null

    // Reusable Paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val hoshiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val lastMoveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(46, 255, 179, 0)
    }
    private val lastMoveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFB300")
    }
    private val lastMoveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFB300")
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#10B981")
    }
    private val pieceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pieceFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val winGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val winCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val tempPath = Path()
    private val tempRect = RectF()

    private var cachedBoardPx = 0f
    private var cachedWoodShader: Shader? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isPanningOrZooming = true
                ghostPoint = null
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val prevScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
                val ratio = scaleFactor / prevScale
                val focusX = detector.focusX
                val focusY = detector.focusY
                translateX = focusX - (focusX - translateX) * ratio
                translateY = focusY - (focusY - translateY) * ratio
                clampTranslation()
                onZoomChanged?.invoke(scaleFactor)
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (scaleFactor > 1.01f) {
                    isPanningOrZooming = true
                    ghostPoint = null
                    translateX -= distanceX
                    translateY -= distanceY
                    clampTranslation()
                    invalidate()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactor > 1.05f) {
                    resetZoom()
                } else {
                    val prev = scaleFactor
                    scaleFactor = 2.0f
                    val ratio = scaleFactor / prev
                    translateX = e.x - (e.x - translateX) * ratio
                    translateY = e.y - (e.y - translateY) * ratio
                    clampTranslation()
                    onZoomChanged?.invoke(scaleFactor)
                    invalidate()
                }
                return true
            }
        }
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)

        val size = when {
            wMode == MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY -> min(w, h)
            wMode == MeasureSpec.EXACTLY -> if (h > 0 && hMode != MeasureSpec.UNSPECIFIED) min(w, h) else w
            hMode == MeasureSpec.EXACTLY -> if (w > 0 && wMode != MeasureSpec.UNSPECIFIED) min(w, h) else h
            else -> min(w, h).coerceAtLeast(300)
        }
        setMeasuredDimension(size, size)
    }

    fun updateBoardState(
        size: Int,
        newBoard: Array<IntArray>,
        lastMovePoint: BoardPoint?,
        newWinLine: List<BoardPoint>,
        currentTurn: Piece,
        enabled: Boolean,
        xStyle: PieceStyle,
        oStyle: PieceStyle,
        surface: BoardSurface,
        hint: BoardPoint?
    ) {
        val sizeChanged = boardSize != size
        boardSize = size
        board = Array(size) { r -> newBoard[r].clone() }

        val newMoveAppeared = lastMovePoint != null && lastMovePoint != lastMove
        lastMove = lastMovePoint
        activeTurnPiece = currentTurn
        interactionEnabled = enabled
        xPieceStyle = xStyle
        oPieceStyle = oStyle

        if (boardSurface != surface) {
            boardSurface = surface
            cachedWoodShader = null
        }

        if (sizeChanged) {
            resetZoom()
        }

        if (newMoveAppeared && lastMovePoint != null) {
            startPiecePlacementAnimation(lastMovePoint)
        }

        if (newWinLine.isNotEmpty() && newWinLine != winLine) {
            winLine = newWinLine
            startWinLineAnimation()
        } else if (newWinLine.isEmpty()) {
            winLine = emptyList()
            winAnimator?.cancel()
            winLineProgress = 1.0f
        }

        if (hint != hintPoint) {
            hintPoint = hint
            if (hint != null) {
                startHintPulse()
            } else {
                hintAnimator?.cancel()
            }
        }
        syncVipAnimationState()
        invalidate()
    }

    fun resetZoom() {
        scaleFactor = 1.0f
        translateX = 0f
        translateY = 0f
        onZoomChanged?.invoke(1.0f)
        invalidate()
    }

    private fun startPiecePlacementAnimation(point: BoardPoint) {
        newestAnimatedPoint = point
        pieceAnimator?.cancel()
        pieceAnimator = ValueAnimator.ofFloat(0.22f, 1.0f).apply {
            duration = 210L
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener {
                pieceAnimProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startWinLineAnimation() {
        winAnimator?.cancel()
        winAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                winLineProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startHintPulse() {
        hintAnimator?.cancel()
        hintAnimator = ValueAnimator.ofFloat(0.25f, 1.0f).apply {
            duration = 700L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                hintPulseFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun syncVipAnimationState() {
        val needsVipAnim = (xPieceStyle.isVip || oPieceStyle.isVip) && isAttachedToWindow
        if (needsVipAnim) {
            if (vipAnimator == null) {
                vipAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 2200L
                    interpolator = LinearInterpolator()
                    repeatMode = ValueAnimator.RESTART
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        vipAnimPhase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            vipAnimator?.cancel()
            vipAnimator = null
            vipAnimPhase = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncVipAnimationState()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pieceAnimator?.cancel()
        winAnimator?.cancel()
        hintAnimator?.cancel()
        vipAnimator?.cancel()
        vipAnimator = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isPanningOrZooming = false
                if (interactionEnabled) {
                    updateGhostPoint(event.x, event.y)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isPanningOrZooming = true
                ghostPoint = null
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && !isPanningOrZooming) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) {
                        if (scaleFactor > 1.01f) {
                            isPanningOrZooming = true
                            ghostPoint = null
                            invalidate()
                        } else if (interactionEnabled) {
                            // Allow sliding finger to preview different cells on 1x zoom
                            updateGhostPoint(event.x, event.y)
                        }
                    } else if (interactionEnabled) {
                        updateGhostPoint(event.x, event.y)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isPanningOrZooming && !scaleDetector.isInProgress && interactionEnabled) {
                    val cell = screenToCell(event.x, event.y)
                    ghostPoint = null
                    invalidate()
                    if (cell != null && board[cell.row][cell.col] == Piece.EMPTY.code) {
                        onCellTapped?.invoke(cell.row, cell.col)
                    }
                } else {
                    ghostPoint = null
                    invalidate()
                }
                isPanningOrZooming = false
            }

            MotionEvent.ACTION_CANCEL -> {
                ghostPoint = null
                isPanningOrZooming = false
                invalidate()
            }
        }
        return true
    }

    private fun updateGhostPoint(screenX: Float, screenY: Float) {
        val cell = screenToCell(screenX, screenY)
        val validCell = if (cell != null && board[cell.row][cell.col] == Piece.EMPTY.code) cell else null
        if (validCell != ghostPoint) {
            ghostPoint = validCell
            invalidate()
        }
    }

    private fun screenToCell(screenX: Float, screenY: Float): BoardPoint? {
        val boardPx = min(width, height).toFloat()
        if (boardPx <= 0f) return null
        val bx = (screenX - translateX) / scaleFactor
        val by = (screenY - translateY) / scaleFactor
        val padding = boardPx * 0.025f
        val gridPx = boardPx - padding * 2f
        val cellSize = gridPx / boardSize
        val col = ((bx - padding) / cellSize).toInt()
        val row = ((by - padding) / cellSize).toInt()
        return if (row in 0 until boardSize && col in 0 until boardSize) {
            BoardPoint(row, col)
        } else null
    }

    private fun clampTranslation() {
        val w = width.toFloat()
        val h = height.toFloat()
        val scaledW = w * scaleFactor
        val scaledH = h * scaleFactor
        val minX = min(0f, w - scaledW)
        val minY = min(0f, h - scaledH)
        translateX = translateX.coerceIn(minX, 0f)
        translateY = translateY.coerceIn(minY, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val boardPx = min(width, height).toFloat()
        if (boardPx <= 0f) return

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor)

        drawBoardSurface(canvas, boardPx)

        val padding = boardPx * 0.025f
        val gridPx = boardPx - padding * 2f
        val cellSize = gridPx / boardSize

        drawGridLinesAndHoshi(canvas, padding, gridPx, cellSize)
        drawLastMoveHighlight(canvas, padding, cellSize)
        drawHintHighlight(canvas, padding, cellSize)
        drawPieces(canvas, padding, cellSize)
        drawGhostPreview(canvas, padding, cellSize)
        drawWinLine(canvas, padding, cellSize)

        canvas.restore()
    }

    private fun drawBoardSurface(canvas: Canvas, boardPx: Float) {
        when (boardSurface) {
            BoardSurface.MINIMAL_CREAM -> {
                bgPaint.shader = null
                bgPaint.color = Color.parseColor("#FAF8F5")
                canvas.drawRect(0f, 0f, boardPx, boardPx, bgPaint)
            }
            BoardSurface.WARM_WOOD -> {
                if (cachedBoardPx != boardPx || cachedWoodShader == null) {
                    cachedBoardPx = boardPx
                    cachedWoodShader = LinearGradient(
                        0f, 0f, boardPx, boardPx,
                        intArrayOf(
                            Color.parseColor("#E8C89B"),
                            Color.parseColor("#DDB37C"),
                            Color.parseColor("#D4A366")
                        ),
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                bgPaint.shader = cachedWoodShader
                canvas.drawRect(0f, 0f, boardPx, boardPx, bgPaint)
                // Subtle procedural wood grain
                grainPaint.color = Color.argb(26, 110, 68, 30)
                val step = boardPx / 14f
                for (i in 1..13) {
                    val y = i * step
                    tempPath.reset()
                    tempPath.moveTo(0f, y)
                    tempPath.cubicTo(
                        boardPx * 0.3f, y - step * 0.25f,
                        boardPx * 0.7f, y + step * 0.25f,
                        boardPx, y
                    )
                    canvas.drawPath(tempPath, grainPaint)
                }
            }
            BoardSurface.EMERALD_GRID -> {
                bgPaint.shader = null
                bgPaint.color = Color.parseColor("#E8F4ED")
                canvas.drawRect(0f, 0f, boardPx, boardPx, bgPaint)
            }
            BoardSurface.DARK_SLATE -> {
                bgPaint.shader = null
                bgPaint.color = Color.parseColor("#161B26")
                canvas.drawRect(0f, 0f, boardPx, boardPx, bgPaint)
            }
        }
    }

    private fun drawGridLinesAndHoshi(
        canvas: Canvas,
        padding: Float,
        gridPx: Float,
        cellSize: Float
    ) {
        val (lineColor, borderColor) = when (boardSurface) {
            BoardSurface.MINIMAL_CREAM -> Color.parseColor("#DDD7CD") to Color.parseColor("#B8B0A4")
            BoardSurface.WARM_WOOD -> Color.parseColor("#9C7044") to Color.parseColor("#6D4926")
            BoardSurface.EMERALD_GRID -> Color.parseColor("#A7CEB8") to Color.parseColor("#549A74")
            BoardSurface.DARK_SLATE -> Color.parseColor("#2A3447") to Color.parseColor("#475569")
        }

        gridPaint.color = lineColor
        gridPaint.strokeWidth = max(1.2f, cellSize * 0.032f)

        val right = padding + gridPx
        val bottom = padding + gridPx

        for (i in 0..boardSize) {
            val pos = padding + i * cellSize
            canvas.drawLine(pos, padding, pos, bottom, gridPaint)
            canvas.drawLine(padding, pos, right, pos, gridPaint)
        }

        borderPaint.color = borderColor
        borderPaint.strokeWidth = max(2.2f, cellSize * 0.06f)
        canvas.drawRect(padding, padding, right, bottom, borderPaint)

        // Draw subtle star-points (hoshi) at key intersections
        hoshiPaint.color = borderColor
        val dotRadius = max(2.5f, cellSize * 0.09f)
        val starIndices = when (boardSize) {
            10 -> listOf(2, 7)
            15 -> listOf(3, 7, 11)
            20 -> listOf(3, 9, 16)
            else -> listOf(boardSize / 2)
        }
        for (r in starIndices) {
            for (c in starIndices) {
                val cx = padding + (c + 0.5f) * cellSize
                val cy = padding + (r + 0.5f) * cellSize
                canvas.drawCircle(cx, cy, dotRadius, hoshiPaint)
            }
        }
    }

    private fun drawLastMoveHighlight(canvas: Canvas, padding: Float, cellSize: Float) {
        val pt = lastMove ?: return
        val left = padding + pt.col * cellSize
        val top = padding + pt.row * cellSize
        val inset = cellSize * 0.05f
        tempRect.set(left + inset, top + inset, left + cellSize - inset, top + cellSize - inset)
        canvas.drawRoundRect(tempRect, cellSize * 0.14f, cellSize * 0.14f, lastMoveFillPaint)
        lastMoveStrokePaint.strokeWidth = max(2f, cellSize * 0.065f)
        canvas.drawRoundRect(tempRect, cellSize * 0.14f, cellSize * 0.14f, lastMoveStrokePaint)
        canvas.drawCircle(
            left + cellSize * 0.82f,
            top + cellSize * 0.18f,
            max(2.5f, cellSize * 0.08f),
            lastMoveDotPaint
        )
    }

    private fun drawHintHighlight(canvas: Canvas, padding: Float, cellSize: Float) {
        val pt = hintPoint ?: return
        if (board[pt.row][pt.col] != Piece.EMPTY.code) return
        val cx = padding + (pt.col + 0.5f) * cellSize
        val cy = padding + (pt.row + 0.5f) * cellSize
        hintPaint.strokeWidth = max(2.5f, cellSize * 0.08f)
        hintPaint.alpha = (110 + (145 * hintPulseFraction)).toInt()
        val radius = cellSize * (0.28f + 0.10f * hintPulseFraction)
        canvas.drawCircle(cx, cy, radius, hintPaint)
    }

    private fun drawPieces(canvas: Canvas, padding: Float, cellSize: Float) {
        for (r in 0 until boardSize) {
            for (c in 0 until boardSize) {
                val code = board[r][c]
                if (code == Piece.EMPTY.code) continue
                val piece = Piece.fromCode(code)
                val cx = padding + (c + 0.5f) * cellSize
                val cy = padding + (r + 0.5f) * cellSize
                val animScale = if (newestAnimatedPoint?.row == r && newestAnimatedPoint?.col == c) {
                    pieceAnimProgress
                } else {
                    1.0f
                }
                val style = if (piece == Piece.X) xPieceStyle else oPieceStyle
                drawStyledPiece(
                    canvas, piece, style, cx, cy, cellSize, animScale, 255,
                    pieceStrokePaint, pieceFillPaint, tempPath, vipAnimPhase
                )
            }
        }
    }

    private fun drawGhostPreview(canvas: Canvas, padding: Float, cellSize: Float) {
        val pt = ghostPoint ?: return
        val cx = padding + (pt.col + 0.5f) * cellSize
        val cy = padding + (pt.row + 0.5f) * cellSize
        val style = if (activeTurnPiece == Piece.X) xPieceStyle else oPieceStyle
        drawStyledPiece(
            canvas, activeTurnPiece, style, cx, cy, cellSize, 0.92f, 110,
            pieceStrokePaint, pieceFillPaint, tempPath, vipAnimPhase
        )
    }

    private fun drawWinLine(canvas: Canvas, padding: Float, cellSize: Float) {
        if (winLine.size < 2) return
        val first = winLine.first()
        val last = winLine.last()
        val startX = padding + (first.col + 0.5f) * cellSize
        val startY = padding + (first.row + 0.5f) * cellSize
        val fullEndX = padding + (last.col + 0.5f) * cellSize
        val fullEndY = padding + (last.row + 0.5f) * cellSize

        val endX = startX + (fullEndX - startX) * winLineProgress
        val endY = startY + (fullEndY - startY) * winLineProgress

        winGlowPaint.color = Color.argb(95, 255, 179, 0)
        winGlowPaint.strokeWidth = cellSize * 0.36f
        canvas.drawLine(startX, startY, endX, endY, winGlowPaint)

        winGlowPaint.color = Color.argb(190, 255, 213, 79)
        winGlowPaint.strokeWidth = cellSize * 0.20f
        canvas.drawLine(startX, startY, endX, endY, winGlowPaint)

        winCorePaint.strokeWidth = max(3f, cellSize * 0.09f)
        canvas.drawLine(startX, startY, endX, endY, winCorePaint)
    }

    companion object {
        fun drawStyledPiece(
            canvas: Canvas,
            piece: Piece,
            pieceStyle: PieceStyle,
            cx: Float,
            cy: Float,
            cellSize: Float,
            scale: Float,
            alpha: Int,
            strokePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            },
            fillPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
            },
            path: Path = Path(),
            animPhase: Float = 0f
        ) {
            if (piece == Piece.EMPTY) return
            val radius = cellSize * 0.33f * scale

            when (pieceStyle) {
                PieceStyle.CLASSIC -> {
                    strokePaint.shader = null
                    strokePaint.strokeWidth = max(2.5f, cellSize * 0.135f * scale)
                    if (piece == Piece.X) {
                        strokePaint.color = Color.parseColor("#E53935")
                        strokePaint.alpha = alpha
                        canvas.drawLine(cx - radius, cy - radius, cx + radius, cy + radius, strokePaint)
                        canvas.drawLine(cx + radius, cy - radius, cx - radius, cy + radius, strokePaint)
                    } else {
                        strokePaint.color = Color.parseColor("#1E88E5")
                        strokePaint.alpha = alpha
                        canvas.drawCircle(cx, cy, radius, strokePaint)
                    }
                }

                PieceStyle.NEON_CYBER -> {
                    val glowColor = if (piece == Piece.X) Color.parseColor("#D500F9") else Color.parseColor("#00E5FF")
                    val coreColor = if (piece == Piece.X) Color.parseColor("#F8BBD0") else Color.parseColor("#E0F7FA")

                    strokePaint.shader = null
                    // Outer neon halo pass
                    strokePaint.color = glowColor
                    strokePaint.alpha = (alpha * 0.28f).toInt()
                    strokePaint.strokeWidth = max(4f, cellSize * 0.28f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, radius, strokePaint)

                    // Mid neon glow pass
                    strokePaint.alpha = (alpha * 0.55f).toInt()
                    strokePaint.strokeWidth = max(3f, cellSize * 0.17f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, radius, strokePaint)

                    // Bright glowing core pass
                    strokePaint.color = coreColor
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = max(1.8f, cellSize * 0.08f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, radius, strokePaint)
                }

                PieceStyle.WOOD_CLASSIC -> {
                    val stoneRadius = cellSize * 0.39f * scale
                    // Drop shadow
                    fillPaint.shader = null
                    fillPaint.color = Color.argb((alpha * 0.28f).toInt(), 0, 0, 0)
                    canvas.drawCircle(cx + stoneRadius * 0.08f, cy + stoneRadius * 0.12f, stoneRadius, fillPaint)

                    if (piece == Piece.X) {
                        // Glossy dark slate Go stone
                        fillPaint.shader = RadialGradient(
                            cx - stoneRadius * 0.3f,
                            cy - stoneRadius * 0.3f,
                            stoneRadius * 1.4f,
                            intArrayOf(
                                Color.argb(alpha, 95, 102, 114),
                                Color.argb(alpha, 30, 34, 42),
                                Color.argb(alpha, 12, 14, 18)
                            ),
                            floatArrayOf(0f, 0.55f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawCircle(cx, cy, stoneRadius, fillPaint)
                        fillPaint.shader = null
                        // Subtle engraved gold-white X mark for clarity
                        strokePaint.shader = null
                        strokePaint.color = Color.argb((alpha * 0.65f).toInt(), 226, 232, 240)
                        strokePaint.strokeWidth = max(1.5f, cellSize * 0.06f * scale)
                        val r = stoneRadius * 0.38f
                        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, strokePaint)
                        canvas.drawLine(cx + r, cy - r, cx - r, cy + r, strokePaint)
                    } else {
                        // Glossy ivory pearl Go stone
                        fillPaint.shader = RadialGradient(
                            cx - stoneRadius * 0.3f,
                            cy - stoneRadius * 0.3f,
                            stoneRadius * 1.4f,
                            intArrayOf(
                                Color.argb(alpha, 255, 255, 255),
                                Color.argb(alpha, 244, 240, 230),
                                Color.argb(alpha, 206, 199, 184)
                            ),
                            floatArrayOf(0f, 0.6f, 1f),
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawCircle(cx, cy, stoneRadius, fillPaint)
                        fillPaint.shader = null
                        strokePaint.shader = null
                        strokePaint.color = Color.argb((alpha * 0.55f).toInt(), 148, 138, 122)
                        strokePaint.strokeWidth = max(1.2f, cellSize * 0.045f * scale)
                        canvas.drawCircle(cx, cy, stoneRadius, strokePaint)
                        canvas.drawCircle(cx, cy, stoneRadius * 0.42f, strokePaint)
                    }
                }

                PieceStyle.SYMBOL -> {
                    val s = cellSize * 0.38f * scale
                    if (piece == Piece.X) {
                        // Crossed crimson/steel swords
                        strokePaint.shader = null
                        strokePaint.color = Color.argb(alpha, 229, 57, 53)
                        strokePaint.strokeWidth = max(2.2f, cellSize * 0.11f * scale)
                        // Blade 1
                        canvas.drawLine(cx - s, cy + s, cx + s, cy - s, strokePaint)
                        // Blade 2
                        canvas.drawLine(cx - s, cy - s, cx + s, cy + s, strokePaint)
                        // Crossguards
                        strokePaint.color = Color.argb(alpha, 245, 158, 11)
                        strokePaint.strokeWidth = max(2f, cellSize * 0.08f * scale)
                        val g = s * 0.42f
                        canvas.drawLine(cx - s * 0.65f - g * 0.4f, cy + s * 0.35f - g * 0.4f, cx - s * 0.35f + g * 0.4f, cy + s * 0.65f + g * 0.4f, strokePaint)
                        canvas.drawLine(cx - s * 0.65f - g * 0.4f, cy - s * 0.35f + g * 0.4f, cx - s * 0.35f + g * 0.4f, cy - s * 0.65f - g * 0.4f, strokePaint)
                    } else {
                        // Crested cobalt & gold shield
                        path.reset()
                        path.moveTo(cx, cy - s)
                        path.lineTo(cx + s * 0.85f, cy - s * 0.65f)
                        path.quadTo(cx + s * 0.85f, cy + s * 0.45f, cx, cy + s)
                        path.quadTo(cx - s * 0.85f, cy + s * 0.45f, cx - s * 0.85f, cy - s * 0.65f)
                        path.close()

                        fillPaint.shader = null
                        fillPaint.color = Color.argb(alpha, 30, 136, 229)
                        canvas.drawPath(path, fillPaint)

                        strokePaint.shader = null
                        strokePaint.color = Color.argb(alpha, 251, 191, 36)
                        strokePaint.strokeWidth = max(1.8f, cellSize * 0.07f * scale)
                        canvas.drawPath(path, strokePaint)

                        // Inner emblem stripe
                        strokePaint.color = Color.argb((alpha * 0.85f).toInt(), 255, 255, 255)
                        strokePaint.strokeWidth = max(1.5f, cellSize * 0.055f * scale)
                        canvas.drawLine(cx, cy - s * 0.55f, cx, cy + s * 0.55f, strokePaint)
                    }
                }

                PieceStyle.VIP_CRIMSON_EMBER -> {
                    val angleRad = animPhase * (2.0 * Math.PI)
                    val pulse = 0.72f + 0.28f * sin(angleRad).toFloat()
                    val r = cellSize * 0.35f * scale

                    // Molten ambient aura
                    fillPaint.shader = RadialGradient(
                        cx, cy, r * 1.38f,
                        intArrayOf(
                            Color.argb((alpha * 0.34f * pulse).toInt(), 255, 109, 0),
                            Color.argb((alpha * 0.14f * pulse).toInt(), 255, 23, 68),
                            Color.argb(0, 255, 23, 68)
                        ),
                        floatArrayOf(0f, 0.58f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(cx, cy, r * 1.38f, fillPaint)
                    fillPaint.shader = null

                    // Outer flame glow
                    strokePaint.shader = null
                    strokePaint.color = Color.parseColor("#FF1744")
                    strokePaint.alpha = (alpha * 0.42f * pulse).toInt()
                    strokePaint.strokeWidth = max(4f, cellSize * 0.26f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Blazing magma mid pass
                    strokePaint.color = Color.parseColor("#FF9100")
                    strokePaint.alpha = (alpha * 0.90f).toInt()
                    strokePaint.strokeWidth = max(2.6f, cellSize * 0.15f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Incandescent core
                    strokePaint.color = Color.parseColor("#FFF8E1")
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = max(1.4f, cellSize * 0.065f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Rotating flame arc on O or fiery center spark on X
                    if (piece == Piece.O) {
                        val arcRect = RectF(cx - r * 1.06f, cy - r * 1.06f, cx + r * 1.06f, cy + r * 1.06f)
                        strokePaint.color = Color.parseColor("#FFD54F")
                        strokePaint.alpha = (alpha * 0.85f).toInt()
                        strokePaint.strokeWidth = max(1.4f, cellSize * 0.05f * scale)
                        val startDeg = animPhase * 360f
                        canvas.drawArc(arcRect, startDeg, 75f, false, strokePaint)
                        canvas.drawArc(arcRect, startDeg + 180f, 75f, false, strokePaint)
                    } else {
                        fillPaint.color = Color.argb(alpha, 255, 241, 118)
                        canvas.drawCircle(cx, cy, max(2f, r * 0.16f * pulse), fillPaint)
                    }

                    // 3 orbiting ember sparks
                    val sparkR = max(1.6f, cellSize * 0.042f * scale)
                    fillPaint.color = Color.argb((alpha * 0.92f).toInt(), 255, 213, 79)
                    for (i in 0..2) {
                        val theta = angleRad + i * (2.0 * Math.PI / 3.0)
                        val orbitDist = r * (1.14f + 0.08f * sin(theta * 2.0).toFloat())
                        val sx = cx + (cos(theta) * orbitDist).toFloat()
                        val sy = cy + (sin(theta) * orbitDist).toFloat()
                        canvas.drawCircle(sx, sy, sparkR, fillPaint)
                    }
                }

                PieceStyle.VIP_PLASMA_VORTEX -> {
                    val angleRad = animPhase * (2.0 * Math.PI)
                    val pulse = 0.74f + 0.26f * sin(angleRad * 2.0).toFloat()
                    val r = cellSize * 0.35f * scale

                    // Quantum prism radial glow
                    fillPaint.shader = RadialGradient(
                        cx, cy, r * 1.42f,
                        intArrayOf(
                            Color.argb((alpha * 0.36f * pulse).toInt(), 6, 182, 212),
                            Color.argb((alpha * 0.18f).toInt(), 168, 85, 247),
                            Color.argb(0, 124, 58, 237)
                        ),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(cx, cy, r * 1.42f, fillPaint)
                    fillPaint.shader = null

                    // Rotating holographic diamond reticle
                    strokePaint.shader = null
                    strokePaint.color = Color.parseColor("#A855F7")
                    strokePaint.alpha = (alpha * 0.45f).toInt()
                    strokePaint.strokeWidth = max(1.2f, cellSize * 0.04f * scale)
                    val retR = r * 1.16f
                    path.reset()
                    for (i in 0..3) {
                        val theta = angleRad + i * (Math.PI / 2.0)
                        val px = cx + (cos(theta) * retR).toFloat()
                        val py = cy + (sin(theta) * retR).toFloat()
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    path.close()
                    canvas.drawPath(path, strokePaint)

                    // Outer electric violet halo
                    strokePaint.color = Color.parseColor("#9333EA")
                    strokePaint.alpha = (alpha * 0.48f * pulse).toInt()
                    strokePaint.strokeWidth = max(4f, cellSize * 0.27f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Cyan plasma arc body
                    strokePaint.color = Color.parseColor("#00E5FF")
                    strokePaint.alpha = (alpha * 0.94f).toInt()
                    strokePaint.strokeWidth = max(2.4f, cellSize * 0.145f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // High-voltage white core
                    strokePaint.color = Color.WHITE
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = max(1.3f, cellSize * 0.058f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Counter-rotating orbital plasma arcs
                    val arcRect = RectF(cx - r * 1.12f, cy - r * 1.12f, cx + r * 1.12f, cy + r * 1.12f)
                    strokePaint.color = Color.parseColor("#38BDF8")
                    strokePaint.alpha = (alpha * 0.85f).toInt()
                    strokePaint.strokeWidth = max(1.4f, cellSize * 0.045f * scale)
                    val deg = animPhase * 360f
                    canvas.drawArc(arcRect, -deg, 65f, false, strokePaint)
                    canvas.drawArc(arcRect, -deg + 180f, 65f, false, strokePaint)

                    // Electric nodes
                    val nodeR = max(1.8f, cellSize * 0.048f * scale)
                    fillPaint.color = Color.argb(alpha, 224, 247, 250)
                    for (i in 0..1) {
                        val theta = -angleRad + i * Math.PI
                        val nx = cx + (cos(theta) * r * 1.12f).toFloat()
                        val ny = cy + (sin(theta) * r * 1.12f).toFloat()
                        canvas.drawCircle(nx, ny, nodeR, fillPaint)
                    }
                }

                PieceStyle.VIP_CELESTIAL_MONARCH -> {
                    val angleRad = animPhase * (2.0 * Math.PI)
                    val shimmer = 0.78f + 0.22f * sin(angleRad * 2.0).toFloat()
                    val r = cellSize * 0.36f * scale

                    // Sovereign Imperial Gold Aura
                    fillPaint.shader = RadialGradient(
                        cx, cy, r * 1.48f,
                        intArrayOf(
                            Color.argb((alpha * 0.42f * shimmer).toInt(), 251, 191, 36),
                            Color.argb((alpha * 0.16f).toInt(), 245, 158, 11),
                            Color.argb(0, 217, 119, 6)
                        ),
                        floatArrayOf(0f, 0.56f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawCircle(cx, cy, r * 1.48f, fillPaint)
                    fillPaint.shader = null

                    // Rotating 8-ray Celestial Starburst
                    strokePaint.shader = null
                    strokePaint.color = Color.parseColor("#FDE047")
                    strokePaint.alpha = (alpha * 0.42f * shimmer).toInt()
                    strokePaint.strokeWidth = max(1.2f, cellSize * 0.036f * scale)
                    for (i in 0..7) {
                        val theta = angleRad + i * (Math.PI / 4.0)
                        val inner = r * 0.55f
                        val outer = r * (if (i % 2 == 0) 1.24f else 1.02f)
                        canvas.drawLine(
                            cx + (cos(theta) * inner).toFloat(),
                            cy + (sin(theta) * inner).toFloat(),
                            cx + (cos(theta) * outer).toFloat(),
                            cy + (sin(theta) * outer).toFloat(),
                            strokePaint
                        )
                    }

                    // Royal base shadow/crimson or bronze edge
                    strokePaint.color = if (piece == Piece.X) Color.parseColor("#DC2626") else Color.parseColor("#B45309")
                    strokePaint.alpha = (alpha * 0.65f).toInt()
                    strokePaint.strokeWidth = max(4.2f, cellSize * 0.25f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Sovereign Metallic Gold body
                    strokePaint.color = Color.parseColor("#FBBF24")
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = max(2.6f, cellSize * 0.155f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Radiant champagne specular rim
                    strokePaint.color = Color.parseColor("#FEF9C3")
                    strokePaint.alpha = alpha
                    strokePaint.strokeWidth = max(1.3f, cellSize * 0.06f * scale)
                    drawPieceGeometry(canvas, piece, cx, cy, r, strokePaint)

                    // Crown / Royal Jewel accents
                    if (piece == Piece.X) {
                        // Ruby & diamond core crest
                        val gemR = max(2.8f, r * 0.22f)
                        fillPaint.color = Color.argb(alpha, 225, 29, 72)
                        canvas.drawCircle(cx, cy, gemR, fillPaint)
                        fillPaint.color = Color.argb(alpha, 254, 240, 138)
                        canvas.drawCircle(cx, cy, gemR * 0.52f, fillPaint)
                    } else {
                        // 4 orbiting sapphire crown jewels along perimeter
                        val jewelR = max(2.0f, cellSize * 0.052f * scale)
                        for (i in 0..3) {
                            val theta = angleRad + i * (Math.PI / 2.0)
                            val jx = cx + (cos(theta) * r).toFloat()
                            val jy = cy + (sin(theta) * r).toFloat()
                            fillPaint.color = Color.argb(alpha, 56, 189, 248)
                            canvas.drawCircle(jx, jy, jewelR, fillPaint)
                            fillPaint.color = Color.argb(alpha, 255, 255, 255)
                            canvas.drawCircle(jx, jy, jewelR * 0.45f, fillPaint)
                        }
                    }

                    // Orbiting diamond glints
                    val glintR = max(1.6f, cellSize * 0.044f * scale)
                    fillPaint.color = Color.argb((alpha * 0.95f).toInt(), 255, 255, 255)
                    for (i in 0..3) {
                        val theta = -angleRad * 1.2 + i * (Math.PI / 2.0)
                        val gx = cx + (cos(theta) * r * 1.25f).toFloat()
                        val gy = cy + (sin(theta) * r * 1.25f).toFloat()
                        canvas.drawCircle(gx, gy, glintR, fillPaint)
                    }
                }
            }
        }

        private fun drawPieceGeometry(
            canvas: Canvas,
            piece: Piece,
            cx: Float,
            cy: Float,
            radius: Float,
            paint: Paint
        ) {
            if (piece == Piece.X) {
                canvas.drawLine(cx - radius, cy - radius, cx + radius, cy + radius, paint)
                canvas.drawLine(cx + radius, cy - radius, cx - radius, cy + radius, paint)
            } else {
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }
    }
}
