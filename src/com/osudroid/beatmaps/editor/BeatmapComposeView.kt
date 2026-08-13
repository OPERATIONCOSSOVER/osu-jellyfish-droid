package com.osudroid.beatmaps.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

enum class BeatmapEditorTool {
    Select,
    Circle,
    Slider,
    Spinner,
    Delete,
}

/**
 * Touch-first osu! playfield used by [BeatmapEditorFragment].
 */
class BeatmapComposeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var objects: List<EditableHitObject> = emptyList()
        set(value) {
            field = value
            if (selectedObjectId != null && value.none { it.editorId == selectedObjectId }) {
                selectedObjectId = null
            }
            invalidate()
        }

    var currentTime = 0
        set(value) {
            field = value
            invalidate()
        }

    var circleSize = 5f
        set(value) {
            field = value
            invalidate()
        }

    var tool = BeatmapEditorTool.Select
        set(value) {
            field = value
            sliderStart = null
            invalidate()
        }

    var selectedObjectId: Long? = null
        private set

    var onPlaceCircle: ((x: Int, y: Int) -> Unit)? = null
    var onPlaceSlider: ((startX: Int, startY: Int, endX: Int, endY: Int) -> Unit)? = null
    var onPlaceSpinner: (() -> Unit)? = null
    var onMoveStarted: ((id: Long) -> Unit)? = null
    var onMoveObject: ((id: Long, x: Int, y: Int) -> Unit)? = null
    var onMoveFinished: ((id: Long) -> Unit)? = null
    var onDeleteObject: ((id: Long) -> Unit)? = null
    var onSelectionChanged: ((EditableHitObject?) -> Unit)? = null

    private val playfield = RectF()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(20, 20, 32) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 110, 145)
        style = Paint.Style.STROKE
        strokeWidth = density(1.5f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 170, 170, 205)
        strokeWidth = density(1f)
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = density(3f) }
    private val objectFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = density(12f)
        isFakeBoldText = true
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 205, 90)
        style = Paint.Style.STROKE
        strokeWidth = density(3f)
    }

    private var draggedObjectId: Long? = null
    private var sliderStart: Pair<Int, Int>? = null
    private var sliderPointer: Pair<Int, Int>? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePlayfield()

        canvas.drawColor(Color.rgb(10, 10, 17))
        canvas.drawRect(playfield, backgroundPaint)
        drawGrid(canvas)
        drawObjects(canvas)
        drawSliderGuide(canvas)
        canvas.drawRect(playfield, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updatePlayfield()
        val mapPosition = screenToMap(event.x, event.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (mapPosition == null) {
                    return false
                }

                when (tool) {
                    BeatmapEditorTool.Select -> {
                        val target = findObjectAt(mapPosition.first, mapPosition.second)
                        select(target)
                        if (target != null && target.kind != EditorHitObjectKind.Spinner) {
                            draggedObjectId = target.editorId
                            onMoveStarted?.invoke(target.editorId)
                        }
                    }

                    BeatmapEditorTool.Delete -> findObjectAt(mapPosition.first, mapPosition.second)?.let {
                        onDeleteObject?.invoke(it.editorId)
                    }

                    BeatmapEditorTool.Circle -> onPlaceCircle?.invoke(mapPosition.first, mapPosition.second)

                    BeatmapEditorTool.Slider -> {
                        if (sliderStart == null) {
                            sliderStart = mapPosition
                            sliderPointer = mapPosition
                            invalidate()
                        } else {
                            val start = sliderStart!!
                            onPlaceSlider?.invoke(start.first, start.second, mapPosition.first, mapPosition.second)
                            sliderStart = null
                            sliderPointer = null
                        }
                    }

                    BeatmapEditorTool.Spinner -> onPlaceSpinner?.invoke()
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (tool == BeatmapEditorTool.Select && draggedObjectId != null && mapPosition != null) {
                    onMoveObject?.invoke(draggedObjectId!!, mapPosition.first, mapPosition.second)
                } else if (tool == BeatmapEditorTool.Slider && sliderStart != null && mapPosition != null) {
                    sliderPointer = mapPosition
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggedObjectId?.let { onMoveFinished?.invoke(it) }
                draggedObjectId = null
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                }
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun selectObject(id: Long?) {
        select(objects.firstOrNull { it.editorId == id })
    }

    private fun select(target: EditableHitObject?) {
        selectedObjectId = target?.editorId
        onSelectionChanged?.invoke(target)
        invalidate()
    }

    private fun drawGrid(canvas: Canvas) {
        for (x in 64 until 512 step 64) {
            val screenX = mapToScreenX(x)
            canvas.drawLine(screenX, playfield.top, screenX, playfield.bottom, gridPaint)
        }

        for (y in 48 until 384 step 48) {
            val screenY = mapToScreenY(y)
            canvas.drawLine(playfield.left, screenY, playfield.right, screenY, gridPaint)
        }

        canvas.drawLine(mapToScreenX(256), playfield.top, mapToScreenX(256), playfield.bottom, borderPaint)
        canvas.drawLine(playfield.left, mapToScreenY(192), playfield.right, mapToScreenY(192), borderPaint)
    }

    private fun drawObjects(canvas: Canvas) {
        val visible = objects
            .filter { abs(it.time - currentTime) <= VISIBLE_TIME_RANGE || currentTime in it.time..it.endTime }
            .sortedByDescending { it.time }

        visible.forEach { obj ->
            val alpha = if (currentTime in obj.time..obj.endTime) 255 else
                (255 * (1f - abs(obj.time - currentTime) / VISIBLE_TIME_RANGE.toFloat())).toInt().coerceIn(45, 255)
            val selected = obj.editorId == selectedObjectId
            val x = mapToScreenX(if (obj.kind == EditorHitObjectKind.Spinner) 256 else obj.x)
            val y = mapToScreenY(if (obj.kind == EditorHitObjectKind.Spinner) 192 else obj.y)
            val radius = mapLengthToScreen((54.4f - 4.48f * circleSize.coerceIn(0f, 10f)).coerceAtLeast(10f))

            if (obj.kind == EditorHitObjectKind.Slider) {
                obj.sliderEnd?.let { end ->
                    objectPaint.color = Color.argb(alpha, 90, 205, 255)
                    objectPaint.style = Paint.Style.STROKE
                    objectPaint.strokeWidth = radius * 1.1f
                    canvas.drawLine(x, y, mapToScreenX(end.first), mapToScreenY(end.second), objectPaint)
                }
            }

            if (obj.kind == EditorHitObjectKind.Spinner) {
                objectFillPaint.color = Color.argb(alpha / 4, 190, 120, 255)
                canvas.drawCircle(x, y, max(radius * 2.4f, density(44f)), objectFillPaint)
            }

            objectFillPaint.color = Color.argb(alpha / 3, 245, 120, 185)
            canvas.drawCircle(x, y, if (obj.kind == EditorHitObjectKind.Spinner) radius * 1.15f else radius, objectFillPaint)
            objectPaint.color = if (selected) Color.rgb(255, 215, 85) else when (obj.kind) {
                EditorHitObjectKind.Circle -> Color.argb(alpha, 255, 125, 190)
                EditorHitObjectKind.Slider -> Color.argb(alpha, 100, 215, 255)
                EditorHitObjectKind.Spinner -> Color.argb(alpha, 205, 145, 255)
            }
            objectPaint.style = Paint.Style.STROKE
            objectPaint.strokeWidth = if (selected) density(4f) else density(2.5f)
            canvas.drawCircle(x, y, if (obj.kind == EditorHitObjectKind.Spinner) radius * 1.15f else radius, objectPaint)

            textPaint.alpha = alpha
            canvas.drawText(
                when (obj.kind) {
                    EditorHitObjectKind.Circle -> if (obj.isNewCombo) "C+" else "C"
                    EditorHitObjectKind.Slider -> if (obj.isNewCombo) "S+" else "S"
                    EditorHitObjectKind.Spinner -> "SP"
                },
                x,
                y - (textPaint.ascent() + textPaint.descent()) / 2,
                textPaint,
            )
        }
        textPaint.alpha = 255
    }

    private fun drawSliderGuide(canvas: Canvas) {
        val start = sliderStart ?: return
        val end = sliderPointer ?: start
        canvas.drawLine(mapToScreenX(start.first), mapToScreenY(start.second), mapToScreenX(end.first), mapToScreenY(end.second), guidePaint)
        canvas.drawCircle(mapToScreenX(start.first), mapToScreenY(start.second), density(8f), guidePaint)
    }

    private fun findObjectAt(mapX: Int, mapY: Int): EditableHitObject? = objects
        .asSequence()
        .filter { abs(it.time - currentTime) <= SELECTION_TIME_RANGE || currentTime in it.time..it.endTime }
        .map { obj ->
            val objectX = if (obj.kind == EditorHitObjectKind.Spinner) 256 else obj.x
            val objectY = if (obj.kind == EditorHitObjectKind.Spinner) 192 else obj.y
            obj to hypot((objectX - mapX).toDouble(), (objectY - mapY).toDouble())
        }
        .filter { it.second <= 70 }
        .minWithOrNull(compareBy<Pair<EditableHitObject, Double>> { it.second }.thenBy { abs(it.first.time - currentTime) })
        ?.first

    private fun updatePlayfield() {
        val padding = density(8f)
        val availableWidth = (width - padding * 2).coerceAtLeast(0f)
        val availableHeight = (height - padding * 2).coerceAtLeast(0f)
        val playfieldWidth = minOf(availableWidth, availableHeight * 4f / 3f)
        val playfieldHeight = playfieldWidth * 3f / 4f
        val left = (width - playfieldWidth) / 2f
        val top = (height - playfieldHeight) / 2f
        playfield.set(left, top, left + playfieldWidth, top + playfieldHeight)
    }

    private fun screenToMap(screenX: Float, screenY: Float): Pair<Int, Int>? {
        if (!playfield.contains(screenX, screenY)) {
            return null
        }
        return (((screenX - playfield.left) / playfield.width()) * 512).toInt().coerceIn(0, 512) to
            (((screenY - playfield.top) / playfield.height()) * 384).toInt().coerceIn(0, 384)
    }

    private fun mapToScreenX(x: Int) = playfield.left + x / 512f * playfield.width()
    private fun mapToScreenY(y: Int) = playfield.top + y / 384f * playfield.height()
    private fun mapLengthToScreen(value: Float) = value / 512f * playfield.width()
    private fun density(value: Float) = value * resources.displayMetrics.density

    companion object {
        private const val VISIBLE_TIME_RANGE = 3000
        private const val SELECTION_TIME_RANGE = 650
    }
}
