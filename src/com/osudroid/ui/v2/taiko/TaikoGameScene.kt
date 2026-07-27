package com.osudroid.ui.v2.taiko

import android.util.Log
import com.osudroid.GameMode
import com.osudroid.beatmaps.Beatmap
import com.osudroid.beatmaps.hitobjects.BankHitSampleInfo
import com.osudroid.beatmaps.hitobjects.HitCircle
import com.osudroid.beatmaps.hitobjects.HitObject
import com.osudroid.beatmaps.hitobjects.HitSampleInfo
import com.osudroid.beatmaps.hitobjects.Slider
import com.osudroid.beatmaps.hitobjects.Spinner
import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.data.BeatmapInfo
import com.osudroid.utils.updateThread
import com.reco1l.andengine.UIScene
import com.reco1l.andengine.box
import com.reco1l.andengine.circle
import com.reco1l.andengine.component.UIComponent
import com.reco1l.andengine.component.UIComponent.Companion.FillParent
import com.reco1l.andengine.container
import com.reco1l.andengine.container.UIContainer
import com.reco1l.andengine.shape.PaintStyle
import com.reco1l.andengine.shape.UIBox
import com.reco1l.andengine.sprite
import com.reco1l.andengine.sprite.ScaleType
import com.reco1l.andengine.text
import com.reco1l.andengine.text.UIText
import com.reco1l.andengine.textButton
import com.reco1l.andengine.ui.UITextButton
import com.reco1l.framework.Color4
import com.reco1l.framework.math.Anchor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.anddev.andengine.input.touch.TouchEvent
import ru.nsu.ccfit.zuev.audio.Status
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.GlobalManager
import ru.nsu.ccfit.zuev.osu.ResourceManager
import ru.nsu.ccfit.zuev.osu.ToastLogger
import ru.nsu.ccfit.zuev.skins.BeatmapSkinManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * A deliberately isolated native osu!taiko beta scene.
 *
 * Beta scores never enter the osu!droid score/replay database. This keeps existing leaderboard,
 * replay, multiplayer, and performance calculations trustworthy while the ruleset is still evolving.
 */
class TaikoGameScene private constructor(private val beatmapInfo: BeatmapInfo) : UIScene() {
    private enum class ObjectKind {
        Don,
        Kat,
        Drumroll,
        Denden
    }

    private data class TaikoObject(
        val kind: ObjectKind,
        val startTime: Double,
        val endTime: Double,
        val isBig: Boolean,
        val samples: List<HitSampleInfo>,
        var judged: Boolean = false,
        var entity: UIComponent? = null
    )

    private val resources = ResourceManager.getInstance()
    private val global = GlobalManager.getInstance()
    private val songService = global.songService
    private val loadingScope = CoroutineScope(Dispatchers.Default)
    private var loadingJob: Job? = null

    private val screenWidth = Config.getRES_WIDTH().toFloat()
    private val screenHeight = Config.getRES_HEIGHT().toFloat()
    private val laneY = screenHeight * 0.39f
    private val targetX = screenWidth * 0.18f
    private val spawnX = screenWidth + 80f
    private val inputTop = screenHeight * 0.72f

    private val playfield = UIContainer()
    private val statusText: UIText
    private val judgementText: UIText
    private val loadingText: UIText
    private val modal: UIContainer
    private lateinit var modalTitle: UIText
    private lateinit var primaryButton: UITextButton
    private lateinit var restartButton: UITextButton

    private var beatmap: Beatmap? = null
    private var objects = emptyList<TaikoObject>()
    private var preempt = 1650.0
    private var greatWindow = 35.0
    private var goodWindow = 80.0
    private var isReady = false
    private var isPaused = false
    private var isFinished = false
    private var judgementTimeRemaining = 0f

    private var score = 0L
    private var combo = 0
    private var maxCombo = 0
    private var greatCount = 0
    private var goodCount = 0
    private var missCount = 0
    private var rollHits = 0

    init {
        sprite {
            width = FillParent
            height = FillParent
            scaleType = ScaleType.Crop
            textureRegion = resources.getTexture(
                if (Config.isSafeBeatmapBg()) "menu-background" else "::background"
            ) ?: resources.getTexture("menu-background")
        }

        box {
            width = FillParent
            height = FillParent
            color = Color4.Black
            alpha = 0.72f
        }

        text {
            x = 30f
            y = 24f
            font = resources.getFont("middleFont")
            color = Color4(0xFFFF80AB)
            text = "osu!taiko (BETA)"
        }

        text {
            x = 30f
            y = 65f
            width = screenWidth * 0.62f
            font = resources.getFont("smallFont")
            text = "${beatmapInfo.artistText} - ${beatmapInfo.titleText} [${beatmapInfo.version}]"
        }

        statusText = text {
            x = screenWidth - 390f
            y = 24f
            width = 360f
            alignment = Anchor.TopRight
            font = resources.getFont("middleFont")
            text = "Score 0  •  Combo 0x  •  100.00%"
        }

        box {
            x = 0f
            y = laneY - 58f
            width = FillParent
            height = 116f
            color = Color4(0xFF181824)
            alpha = 0.95f
        }

        box {
            x = 0f
            y = laneY - 2f
            width = FillParent
            height = 4f
            color = Color4(0xFF55556A)
        }

        // Static hit target.
        circle {
            x = targetX - 50f
            y = laneY - 50f
            width = 100f
            height = 100f
            color = Color4(0xFFFFFFFF)
            alpha = 0.24f
            paintStyle = PaintStyle.Outline
            lineWidth = 6f
        }

        attachChild(playfield)

        judgementText = text {
            x = targetX - 85f
            y = laneY + 82f
            width = 170f
            alignment = Anchor.TopCenter
            font = resources.getFont("middleFont")
            text = ""
        }

        createInputZones()

        loadingText = text {
            x = screenWidth / 2f - 180f
            y = screenHeight / 2f - 20f
            width = 360f
            alignment = Anchor.Center
            font = resources.getFont("middleFont")
            text = "Loading osu!taiko (BETA)…"
        }

        modal = container {
            x = screenWidth / 2f - 275f
            y = screenHeight / 2f - 190f
            width = 550f
            height = 380f
            isVisible = false

            background = UIBox().apply {
                cornerRadius = 20f
                color = Color4(0xFF202033)
                alpha = 0.98f
            }

            modalTitle = text {
                x = 30f
                y = 34f
                width = 490f
                alignment = Anchor.TopCenter
                font = resources.getFont("middleFont")
                text = "osu!taiko (BETA)\nPaused"
            }

            primaryButton = textButton {
                x = 35f
                y = 178f
                width = 480f
                height = 54f
                text = "Resume"
                onActionUp = { resume() }
            }

            restartButton = textButton {
                x = 35f
                y = 244f
                width = 232f
                height = 54f
                text = "Restart"
                onActionUp = { restart() }
            }

            textButton {
                x = 283f
                y = 244f
                width = 232f
                height = 54f
                text = "Exit to songs"
                onActionUp = { exitToSongMenu() }
            }

            text {
                x = 35f
                y = 320f
                width = 480f
                alignment = Anchor.TopCenter
                font = resources.getFont("smallFont")
                color = Color4(0xFFFF80AB)
                text = "Beta scores are kept separate and are not uploaded."
            }
        }
        hideModal()

        setOnSceneTouchListener { _, event -> handleTouch(event) }
    }

    private fun createInputZones() {
        val zoneWidth = screenWidth / 4f
        val zoneHeight = screenHeight - inputTop

        repeat(4) { index ->
            val isKat = index == 0 || index == 3

            box {
                x = zoneWidth * index
                y = inputTop
                width = zoneWidth
                height = zoneHeight
                color = if (isKat) KAT_COLOR else DON_COLOR
                alpha = 0.16f
            }

            text {
                x = zoneWidth * index
                y = inputTop + zoneHeight / 2f - 16f
                width = zoneWidth
                alignment = Anchor.TopCenter
                font = resources.getFont("middleFont")
                color = if (isKat) KAT_COLOR else DON_COLOR
                text = if (isKat) "KAT" else "DON"
            }
        }
    }

    private fun beginLoading() {
        loadingJob = loadingScope.launch {
            try {
                val parsed = BeatmapParser(beatmapInfo.path, this, beatmapInfo.md5)
                    .parse(true, GameMode.Standard)

                if (parsed.general.mode != 1) {
                    throw IllegalArgumentException("Selected beatmap is not a native osu!taiko map")
                }

                BeatmapSkinManager.getInstance().loadBeatmapSkin(parsed.beatmapsetPath)

                val taikoObjects = parsed.hitObjects.objects.map { it.toTaikoObject() }
                val od = parsed.difficulty.od.toDouble().coerceIn(0.0, 10.0)
                val calculatedGreatWindow = (50.0 - 3.0 * od).coerceAtLeast(20.0)
                val calculatedGoodWindow = (120.0 - 8.0 * od).coerceAtLeast(40.0)

                if (!songService.preLoad(beatmapInfo.audioPath)) {
                    throw IllegalStateException("Unable to load beatmap audio")
                }

                songService.setSpeed(1f)
                songService.setAdjustPitch(false)
                songService.setVolume(Config.getBgmVolume())
                songService.setGaming(true)
                songService.seekTo(0)

                updateThread {
                    beatmap = parsed
                    objects = taikoObjects
                    greatWindow = calculatedGreatWindow
                    goodWindow = calculatedGoodWindow
                    loadingText.isVisible = false
                    isReady = true
                    songService.play()
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    return@launch
                }

                Log.e("TaikoGameScene", "Failed to load osu!taiko beta gameplay", e)
                updateThread {
                    loadingText.text = "Could not load this osu!taiko map.\n${e.message.orEmpty()}"
                    modalTitle.text = "osu!taiko (BETA)\nLoad failed"
                    primaryButton.isVisible = false
                    restartButton.text = "Try again"
                    showModal()
                }
            }
        }
    }

    private fun HitObject.toTaikoObject(): TaikoObject {
        val bankSamples = samples.filterIsInstance<BankHitSampleInfo>()
        val isKat = bankSamples.any {
            it.name == BankHitSampleInfo.HIT_WHISTLE || it.name == BankHitSampleInfo.HIT_CLAP
        }
        val isBig = bankSamples.any { it.name == BankHitSampleInfo.HIT_FINISH }

        val kind = when (this) {
            is HitCircle -> if (isKat) ObjectKind.Kat else ObjectKind.Don
            is Slider -> ObjectKind.Drumroll
            is Spinner -> ObjectKind.Denden
            else -> ObjectKind.Don
        }

        return TaikoObject(kind, startTime, endTime, isBig, samples)
    }

    override fun onManagedUpdate(deltaTimeSec: Float) {
        if (isReady && !isPaused && !isFinished) {
            val now = songService.positionPrecise

            processObjects(now)
            updateStatus()

            if (objects.isNotEmpty() && now > objects.last().endTime + 1800) {
                finish()
            } else if (songService.status == Status.STOPPED && now > 0) {
                finish()
            }
        }

        if (judgementTimeRemaining > 0f) {
            judgementTimeRemaining -= deltaTimeSec
            if (judgementTimeRemaining <= 0f) {
                judgementText.text = ""
            }
        }

        super.onManagedUpdate(deltaTimeSec)
    }

    private fun processObjects(now: Double) {
        for (obj in objects) {
            if (obj.judged) {
                continue
            }

            if (obj.entity == null && now >= obj.startTime - preempt && now <= obj.endTime + goodWindow) {
                val entity = createObjectEntity(obj)
                obj.entity = entity
                playfield.attachChild(entity)
            }

            obj.entity?.let { entity ->
                val x = targetX + ((obj.startTime - now) / preempt * (spawnX - targetX)).toFloat()
                entity.x = x - entity.width / 2f
                entity.y = laneY - entity.height / 2f
            }

            when (obj.kind) {
                ObjectKind.Don, ObjectKind.Kat -> {
                    if (now - obj.startTime > goodWindow) {
                        registerMiss(obj)
                    }
                }

                ObjectKind.Drumroll, ObjectKind.Denden -> {
                    if (now > obj.endTime) {
                        expire(obj)
                    }
                }
            }
        }
    }

    private fun createObjectEntity(obj: TaikoObject): UIComponent = when (obj.kind) {
        ObjectKind.Don, ObjectKind.Kat -> com.reco1l.andengine.shape.UICircle().apply {
            val diameter = if (obj.isBig) 96f else 72f
            width = diameter
            height = diameter
            color = if (obj.kind == ObjectKind.Kat) KAT_COLOR else DON_COLOR

            if (obj.isBig) {
                circle {
                    x = 7f
                    y = 7f
                    width = diameter - 14f
                    height = diameter - 14f
                    color = Color4.White
                    paintStyle = PaintStyle.Outline
                    lineWidth = 5f
                }
            }
        }

        ObjectKind.Drumroll, ObjectKind.Denden -> UIBox().apply {
            val durationWidth = max(
                80f,
                ((obj.endTime - obj.startTime) / preempt * (spawnX - targetX)).toFloat()
            )
            width = durationWidth
            height = if (obj.kind == ObjectKind.Denden) 64f else 42f
            cornerRadius = height / 2f
            color = if (obj.kind == ObjectKind.Denden) Color4(0xFFFFC107) else Color4(0xFFFF7043)
            alpha = 0.88f
        }
    }

    private fun handleTouch(event: TouchEvent): Boolean {
        if (!event.isActionDown || !isReady || isPaused || isFinished || event.y < inputTop) {
            return false
        }

        val isKat = event.x < screenWidth / 4f || event.x >= screenWidth * 3f / 4f
        registerInput(isKat, songService.positionPrecise)
        return true
    }

    private fun registerInput(isKat: Boolean, now: Double) {
        val activeRoll = objects.firstOrNull {
            !it.judged &&
                (it.kind == ObjectKind.Drumroll || it.kind == ObjectKind.Denden) &&
                now in it.startTime..it.endTime
        }

        if (activeRoll != null) {
            rollHits++
            score += if (activeRoll.kind == ObjectKind.Denden) 100 else 50
            showJudgement(if (activeRoll.kind == ObjectKind.Denden) "DEN!" else "ROLL", Color4(0xFFFFC107))
            playInputSound(isKat)
            return
        }

        val candidate = objects
            .asSequence()
            .filter {
                !it.judged &&
                    (it.kind == ObjectKind.Don || it.kind == ObjectKind.Kat) &&
                    abs(now - it.startTime) <= goodWindow
            }
            .minByOrNull { abs(now - it.startTime) }

        if (candidate == null) {
            playInputSound(isKat)
            return
        }

        val expectsKat = candidate.kind == ObjectKind.Kat
        if (expectsKat != isKat) {
            playInputSound(isKat)
            return
        }

        val offset = abs(now - candidate.startTime)
        val multiplier = if (candidate.isBig) 2 else 1

        if (offset <= greatWindow) {
            greatCount++
            combo++
            score += (300L + combo * 12L) * multiplier
            showJudgement("GREAT", Color4(0xFFFFD54F))
        } else {
            goodCount++
            combo++
            score += (100L + combo * 4L) * multiplier
            showJudgement("GOOD", Color4(0xFF81D4FA))
        }

        maxCombo = max(maxCombo, combo)
        candidate.samples.forEach { sample ->
            com.osudroid.game.GameplayHitSampleInfo.obtain().also {
                it.init(sample)
                it.play()
                it.release()
            }
        }
        expire(candidate)
    }

    private fun playInputSound(isKat: Boolean) {
        val name = if (isKat) BankHitSampleInfo.HIT_WHISTLE else BankHitSampleInfo.HIT_NORMAL
        resources.getSound(name, false)?.play()
    }

    private fun registerMiss(obj: TaikoObject) {
        missCount++
        combo = 0
        showJudgement("MISS", Color4(0xFFB0BEC5))
        expire(obj)
    }

    private fun expire(obj: TaikoObject) {
        obj.judged = true
        obj.entity?.detachSelf()
        obj.entity = null
    }

    private fun showJudgement(text: String, color: Color4) {
        judgementText.text = text
        judgementText.color = color
        judgementTimeRemaining = 0.35f
    }

    private fun updateStatus() {
        val judged = greatCount + goodCount + missCount
        val accuracy = if (judged == 0) 100.0 else (greatCount * 2.0 + goodCount) / (judged * 2.0) * 100

        statusText.text = String.format(
            Locale.US,
            "Score %,d  •  Combo %dx  •  %.2f%%",
            score,
            combo,
            accuracy
        )
    }

    fun pause() {
        if (!isReady || isPaused || isFinished) {
            return
        }

        isPaused = true
        songService.pause()
        modalTitle.text = "osu!taiko (BETA)\nPaused"
        primaryButton.apply {
            isVisible = true
            text = "Resume"
            onActionUp = { resume() }
        }
        restartButton.isVisible = true
        showModal()
    }

    fun togglePause() {
        if (isFinished) {
            exitToSongMenu()
            return
        }

        if (isPaused) {
            resume()
        } else {
            pause()
        }
    }

    private fun resume() {
        if (!isPaused || isFinished) {
            return
        }

        hideModal()
        isPaused = false
        songService.play()
    }

    private fun finish() {
        if (isFinished) {
            return
        }

        isFinished = true
        songService.pause()
        updateStatus()

        val judged = greatCount + goodCount + missCount
        val accuracy = if (judged == 0) 100.0 else (greatCount * 2.0 + goodCount) / (judged * 2.0) * 100

        modalTitle.text = String.format(
            Locale.US,
            "osu!taiko (BETA) complete\nScore %,d  •  %.2f%%\n%d GREAT  •  %d GOOD  •  %d MISS\nMax combo %dx  •  %d roll hits",
            score,
            accuracy,
            greatCount,
            goodCount,
            missCount,
            maxCombo,
            rollHits
        )
        primaryButton.apply {
            isVisible = true
            text = "Play again"
            onActionUp = { restart() }
        }
        restartButton.isVisible = false
        showModal()
    }

    private fun showModal() {
        modal.x = screenWidth / 2f - 275f
        modal.isVisible = true
    }

    private fun hideModal() {
        // Invisible UI containers still participate in legacy AndEngine touch traversal.
        modal.isVisible = false
        modal.x = screenWidth + 1000f
    }

    private fun restart() {
        cleanup()
        start(beatmapInfo)
    }

    fun exitToSongMenu() {
        cleanup()
        val songMenu = global.songMenu
        global.engine.scene = songMenu.scene
        songMenu.playMusic(beatmapInfo.audioPath, beatmapInfo.previewTime)
    }

    private fun cleanup() {
        loadingJob?.cancel()
        loadingJob = null
        loadingScope.cancel()
        songService.stop()
        songService.setGaming(false)
        BeatmapSkinManager.setSkinEnabled(false)
        BeatmapSkinManager.getInstance().clearSkin()
    }

    companion object {
        private val DON_COLOR = Color4(0xFFEF5350)
        private val KAT_COLOR = Color4(0xFF42A5F5)

        @JvmStatic
        fun start(beatmapInfo: BeatmapInfo) {
            if (beatmapInfo.beatmapMode != 1) {
                ToastLogger.showText("This is not a native osu!taiko beatmap.", true)
                return
            }

            TaikoGameScene(beatmapInfo).also {
                GlobalManager.getInstance().engine.scene = it
                it.beginLoading()
            }
        }
    }
}
