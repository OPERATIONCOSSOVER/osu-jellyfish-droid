package com.osudroid.beatmaps.editor

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.edlplan.ui.fragment.BaseFragment
import com.osudroid.beatmaps.Beatmap
import com.osudroid.beatmaps.BeatmapCache
import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.data.BeatmapInfo
import com.osudroid.data.DatabaseManager
import com.osudroid.utils.async
import com.osudroid.utils.mainThread
import com.osudroid.utils.updateThread
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import ru.nsu.ccfit.zuev.audio.Status
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.menu.SongMenu
import ru.nsu.ccfit.zuev.osuplus.R

/**
 * First functional compose editor for osu!droid.
 *
 * It intentionally starts with the three stable hit-object types and a touch-first workflow. Existing object lines,
 * hitsounds, slider data, and unsupported properties remain intact unless the user edits that object.
 */
class BeatmapEditorFragment : BaseFragment() {
    override val layoutID = R.layout.beatmap_editor_fragment

    private lateinit var menu: SongMenu
    private lateinit var beatmapInfo: BeatmapInfo
    private lateinit var document: EditableBeatmapDocument
    private lateinit var beatmap: Beatmap
    private lateinit var playfield: BeatmapComposeView
    private lateinit var timeline: SeekBar
    private lateinit var timeText: TextView
    private lateinit var statusText: TextView
    private lateinit var selectionText: TextView
    private lateinit var playPause: Button
    private lateinit var newCombo: CheckBox

    private val handler = Handler(Looper.getMainLooper())
    private val nextEditorId = AtomicLong(-1)
    private val undoStack = ArrayDeque<List<EditableHitObject>>()
    private val redoStack = ArrayDeque<List<EditableHitObject>>()
    private var objects = mutableListOf<EditableHitObject>()
    private var currentTime = 0
    private var maxTime = 1
    private var isReady = false
    private var isDirty = false
    private var isUserSeeking = false
    private var isStartingTestPlay = false
    private lateinit var pendingMetadata: EditableBeatmapMetadata
    private var dragSnapshot: List<EditableHitObject>? = null
    private val beatDivisor = 4

    private val playbackUpdater = object : Runnable {
        override fun run() {
            if (isReady && !isUserSeeking) {
                GlobalManager.getInstance().songService?.let { service ->
                    if (service.status == Status.PLAYING) {
                        setCurrentTime(service.position, seekAudio = false, snap = false)
                    }
                    playPause.setText(if (service.status == Status.PLAYING) R.string.editor_pause else R.string.editor_play)
                }
            }
            handler.postDelayed(this, 33)
        }
    }

    override fun onLoadView() {
        playfield = findViewById(R.id.editorPlayfield)!!
        timeline = findViewById(R.id.editorTimeline)!!
        timeText = findViewById(R.id.editorTime)!!
        statusText = findViewById(R.id.editorStatus)!!
        selectionText = findViewById(R.id.editorSelection)!!
        playPause = findViewById(R.id.editorPlayPause)!!
        newCombo = findViewById(R.id.editorNewCombo)!!

        findViewById<TextView>(R.id.editorTitle)!!.text = beatmapInfo.fullBeatmapName
        bindToolbar()
        bindTools()
        bindTimeline()
        bindPlayfield()
        setControlsEnabled(false)
        loadBeatmap()
        handler.post(playbackUpdater)
    }

    fun show(menu: SongMenu, beatmapInfo: BeatmapInfo) {
        this.menu = menu
        this.beatmapInfo = beatmapInfo
        show()
    }

    override fun dismiss() {
        if (isDirty) {
            com.reco1l.osu.ui.MessageDialog()
                .setTitle(getString(R.string.editor_unsaved_title))
                .setMessage(getString(R.string.editor_unsaved_message))
                .addButton(getString(R.string.editor_save)) {
                    save { super.dismiss() }
                }
                .addButton(getString(R.string.editor_discard)) {
                    isDirty = false
                    super.dismiss()
                }
                .addButton(getString(R.string.editor_cancel)) { it.dismiss() }
                .show()
            return
        }
        super.dismiss()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(playbackUpdater)
        if (!isStartingTestPlay) {
            GlobalManager.getInstance().songService?.pause()
        }
        super.onDestroyView()
    }

    private fun loadBeatmap() {
        statusText.setText(R.string.editor_loading)
        async {
            try {
                val file = File(beatmapInfo.path)
                val loadedDocument = EditableBeatmapDocument.open(file)
                val parsedBeatmap = BeatmapParser(file).parse(true)

                mainThread {
                    document = loadedDocument
                    beatmap = parsedBeatmap
                    pendingMetadata = beatmapInfo.toEditableMetadata()
                    objects = loadedDocument.objects.toMutableList()
                    playfield.objects = objects
                    playfield.circleSize = parsedBeatmap.difficulty.gameplayCS
                    maxTime = maxOf(
                        GlobalManager.getInstance().songService?.length ?: 0,
                        objects.maxOfOrNull(EditableHitObject::endTime)?.plus(5000) ?: 1,
                    ).coerceAtLeast(1)
                    timeline.max = maxTime
                    val service = GlobalManager.getInstance().songService
                    service?.pause()
                    setCurrentTime((service?.position ?: beatmapInfo.previewTime).coerceAtLeast(0), seekAudio = false, snap = true)
                    isReady = true
                    setControlsEnabled(true)
                    updateStatus()
                }
            } catch (e: Exception) {
                mainThread {
                    statusText.text = getString(R.string.editor_load_failed, e.message ?: e.javaClass.simpleName)
                    Toast.makeText(requireContext(), statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun bindToolbar() {
        findViewById<View>(R.id.editorClose)!!.setOnClickListener { dismiss() }
        findViewById<View>(R.id.editorSave)!!.setOnClickListener { save() }
        findViewById<View>(R.id.editorTest)!!.setOnClickListener {
            save {
                isDirty = false
                isStartingTestPlay = true
                super.dismiss()
                updateThread {
                    if (menu.selectedBeatmap?.filename != beatmapInfo.filename) {
                        menu.selectBeatmap(beatmapInfo, false)
                    }
                    menu.selectBeatmap(beatmapInfo, false)
                }
            }
        }
        findViewById<View>(R.id.editorSetup)!!.setOnClickListener {
            BeatmapSetupFragment().show(pendingMetadata) { updated ->
                pendingMetadata = updated
                findViewById<TextView>(R.id.editorTitle)?.text = buildBeatmapName(updated)
                isDirty = true
                updateStatus()
            }
        }
        findViewById<View>(R.id.editorUndo)!!.setOnClickListener { undo() }
        findViewById<View>(R.id.editorRedo)!!.setOnClickListener { redo() }
    }

    private fun bindTools() {
        val tools = mapOf(
            R.id.editorToolSelect to BeatmapEditorTool.Select,
            R.id.editorToolCircle to BeatmapEditorTool.Circle,
            R.id.editorToolSlider to BeatmapEditorTool.Slider,
            R.id.editorToolSpinner to BeatmapEditorTool.Spinner,
            R.id.editorToolDelete to BeatmapEditorTool.Delete,
        )
        tools.forEach { (id, tool) ->
            findViewById<View>(id)!!.setOnClickListener {
                playfield.tool = tool
                tools.forEach { (otherId, otherTool) -> findViewById<View>(otherId)?.alpha = if (otherTool == tool) 1f else 0.55f }
            }
        }
        findViewById<View>(R.id.editorToolSelect)!!.performClick()

        newCombo.setOnCheckedChangeListener { _, enabled ->
            val selectedId = playfield.selectedObjectId ?: return@setOnCheckedChangeListener
            replaceObject(selectedId, recordUndo = true) { it.withNewCombo(enabled) }
        }
    }

    private fun bindTimeline() {
        timeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = true
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setCurrentTime(progress, seekAudio = false, snap = false)
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isUserSeeking = false
                setCurrentTime(seekBar.progress, seekAudio = true, snap = true)
            }
        })

        playPause.setOnClickListener {
            GlobalManager.getInstance().songService?.let { service ->
                if (service.status == Status.PLAYING) service.pause() else {
                    service.seekTo(currentTime)
                    service.play()
                }
            }
        }
        findViewById<View>(R.id.editorPreviousSnap)!!.setOnClickListener { stepTimeline(-1) }
        findViewById<View>(R.id.editorNextSnap)!!.setOnClickListener { stepTimeline(1) }
        findViewById<View>(R.id.editorObjectEarlier)!!.setOnClickListener { moveSelectedInTime(-1) }
        findViewById<View>(R.id.editorObjectLater)!!.setOnClickListener { moveSelectedInTime(1) }
    }

    private fun bindPlayfield() {
        playfield.onPlaceCircle = { x, y ->
            commitChange {
                objects += EditableHitObject.circle(nextEditorId.getAndDecrement(), x, y, currentTime, newCombo.isChecked)
                selectNewest()
            }
        }
        playfield.onPlaceSlider = { startX, startY, endX, endY ->
            commitChange {
                objects += EditableHitObject.slider(nextEditorId.getAndDecrement(), startX, startY, endX, endY, currentTime, newCombo.isChecked)
                selectNewest()
            }
        }
        playfield.onPlaceSpinner = {
            val endTime = nextMeasureTime(currentTime)
            commitChange {
                objects += EditableHitObject.spinner(nextEditorId.getAndDecrement(), currentTime, endTime, newCombo.isChecked)
                selectNewest()
            }
        }
        playfield.onDeleteObject = { id ->
            commitChange {
                objects.removeAll { it.editorId == id }
                playfield.selectObject(null)
            }
        }
        playfield.onMoveStarted = {
            dragSnapshot = objects.toList()
        }
        playfield.onMoveObject = { id, x, y -> replaceObject(id, recordUndo = false) { it.moveTo(x, y) } }
        playfield.onMoveFinished = {
            val snapshot = dragSnapshot
            dragSnapshot = null
            if (snapshot != null && snapshot != objects) {
                undoStack.addLast(snapshot)
                if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
                redoStack.clear()
                updateStatus()
            }
        }
        playfield.onSelectionChanged = { selected ->
            selectionText.text = selected?.let {
                getString(R.string.editor_selected_object, it.kind.name, formatTime(it.time))
            } ?: getString(R.string.editor_nothing_selected)
            if (selected != null && newCombo.isChecked != selected.isNewCombo) {
                newCombo.setOnCheckedChangeListener(null)
                newCombo.isChecked = selected.isNewCombo
                bindNewComboListener()
            }
        }
    }

    private fun bindNewComboListener() {
        newCombo.setOnCheckedChangeListener { _, enabled ->
            val selectedId = playfield.selectedObjectId ?: return@setOnCheckedChangeListener
            replaceObject(selectedId, recordUndo = true) { it.withNewCombo(enabled) }
        }
    }

    private fun save(onSaved: (() -> Unit)? = null) {
        if (!isReady) return
        setControlsEnabled(false)
        statusText.setText(R.string.editor_saving)
        val snapshot = objects.toList()
        val oldMd5 = beatmapInfo.md5

        async {
            try {
                document.save(snapshot, pendingMetadata)
                val parsed = BeatmapParser(document.file).parse(true)
                val updated = com.osudroid.data.BeatmapInfo(parsed, beatmapInfo.dateImported, false).also { it.status = beatmapInfo.status }
                BeatmapCache.invalidate(oldMd5)
                DatabaseManager.beatmapInfoTable.update(updated)

                mainThread {
                    beatmap = parsed
                    beatmapInfo.apply(updated)
                    pendingMetadata = updated.toEditableMetadata()
                    objects = document.objects.toMutableList()
                    playfield.objects = objects
                    undoStack.clear()
                    redoStack.clear()
                    isDirty = false
                    setControlsEnabled(true)
                    updateStatus()
                    updateThread { menu.reloadCurrentSelection() }
                    Toast.makeText(requireContext(), R.string.editor_saved, Toast.LENGTH_SHORT).show()
                    onSaved?.invoke()
                }
            } catch (e: Exception) {
                mainThread {
                    setControlsEnabled(true)
                    statusText.text = getString(R.string.editor_save_failed, e.message ?: e.javaClass.simpleName)
                    Toast.makeText(requireContext(), statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun commitChange(change: () -> Unit) {
        undoStack.addLast(objects.toList())
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        change()
        isDirty = true
        refreshObjects()
    }

    private fun replaceObject(id: Long, recordUndo: Boolean, transform: (EditableHitObject) -> EditableHitObject) {
        val index = objects.indexOfFirst { it.editorId == id }
        if (index < 0) return
        val replacement = transform(objects[index])
        if (replacement == objects[index]) return

        if (recordUndo) {
            undoStack.addLast(objects.toList())
            if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
        }
        objects[index] = replacement
        isDirty = true
        refreshObjects()
        playfield.selectObject(id)
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(objects.toList())
        objects = undoStack.removeLast().toMutableList()
        isDirty = true
        refreshObjects()
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(objects.toList())
        objects = redoStack.removeLast().toMutableList()
        isDirty = true
        refreshObjects()
    }

    private fun refreshObjects() {
        playfield.objects = objects.toList()
        updateStatus()
    }

    private fun selectNewest() {
        playfield.objects = objects.toList()
        playfield.selectObject(objects.lastOrNull()?.editorId)
    }

    private fun moveSelectedInTime(direction: Int) {
        val selectedId = playfield.selectedObjectId ?: return
        val selected = objects.firstOrNull { it.editorId == selectedId } ?: return
        val target = adjacentSnap(selected.time, direction)
        replaceObject(selectedId, recordUndo = true) { it.moveInTime(target) }
        setCurrentTime(target, seekAudio = true, snap = false)
    }

    private fun stepTimeline(direction: Int) {
        setCurrentTime(adjacentSnap(currentTime, direction), seekAudio = true, snap = false)
    }

    private fun adjacentSnap(time: Int, direction: Int): Int {
        val point = beatmap.controlPoints.timing.controlPointAt(time.toDouble())
        val step = (point.msPerBeat / beatDivisor).coerceAtLeast(1.0)
        return BeatSnapper.snap((time + direction * step * 0.75).toInt(), beatmap.controlPoints.timing, beatDivisor)
            .coerceIn(0, maxTime)
    }

    private fun nextMeasureTime(time: Int): Int {
        val point = beatmap.controlPoints.timing.controlPointAt(time.toDouble())
        return (time + point.msPerBeat * point.timeSignature).toInt().coerceIn(time + 1, maxTime)
    }

    private fun setCurrentTime(time: Int, seekAudio: Boolean, snap: Boolean) {
        val target = (if (snap && this::beatmap.isInitialized) BeatSnapper.snap(time, beatmap.controlPoints.timing, beatDivisor) else time)
            .coerceIn(0, maxTime)
        currentTime = target
        timeline.progress = target
        timeText.text = formatTime(target)
        playfield.currentTime = target
        if (seekAudio) GlobalManager.getInstance().songService?.seekTo(target)
    }

    private fun updateStatus() {
        statusText.text = getString(
            R.string.editor_status,
            objects.count { it.kind == EditorHitObjectKind.Circle },
            objects.count { it.kind == EditorHitObjectKind.Slider },
            objects.count { it.kind == EditorHitObjectKind.Spinner },
            if (isDirty) getString(R.string.editor_modified) else getString(R.string.editor_saved_state),
        )
        findViewById<View>(R.id.editorUndo)?.isEnabled = undoStack.isNotEmpty()
        findViewById<View>(R.id.editorRedo)?.isEnabled = redoStack.isNotEmpty()
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(
            R.id.editorSave, R.id.editorTest, R.id.editorSetup, R.id.editorUndo, R.id.editorRedo,
            R.id.editorToolSelect, R.id.editorToolCircle, R.id.editorToolSlider, R.id.editorToolSpinner,
            R.id.editorToolDelete, R.id.editorNewCombo, R.id.editorPlayPause, R.id.editorPreviousSnap,
            R.id.editorNextSnap, R.id.editorObjectEarlier, R.id.editorObjectLater, R.id.editorTimeline,
        ).forEach { findViewById<View>(it)?.isEnabled = enabled }
        playfield.isEnabled = enabled
    }

    private fun formatTime(milliseconds: Int): String {
        val minutes = milliseconds / 60000
        val seconds = milliseconds / 1000 % 60
        val millis = milliseconds % 1000
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
    }

    private fun BeatmapInfo.toEditableMetadata() = EditableBeatmapMetadata(
        title = title,
        titleUnicode = titleUnicode,
        artist = artist,
        artistUnicode = artistUnicode,
        creator = creator,
        version = version,
        source = source,
        tags = tags,
    )

    private fun buildBeatmapName(metadata: EditableBeatmapMetadata) =
        "${metadata.artist.ifBlank { "Unknown Artist" }} - ${metadata.title.ifBlank { "Unknown Title" }} " +
            "(${metadata.creator.ifBlank { "Unknown Creator" }}) [${metadata.version.ifBlank { "Unknown Version" }}]"

    companion object {
        private const val MAX_HISTORY = 100
    }
}
