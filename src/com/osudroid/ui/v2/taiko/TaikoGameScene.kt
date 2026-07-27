package com.osudroid.ui.v2.taiko

import android.util.Log
import com.osudroid.GameMode
import com.osudroid.beatmaps.hitobjects.BankHitSampleInfo
import com.osudroid.beatmaps.hitobjects.HitCircle
import com.osudroid.beatmaps.hitobjects.HitObject
import com.osudroid.beatmaps.hitobjects.HitSampleInfo
import com.osudroid.beatmaps.hitobjects.Slider
import com.osudroid.beatmaps.hitobjects.Spinner
import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.data.BeatmapInfo
import com.osudroid.ui.v2.hud.HUDElement
import com.osudroid.ui.v2.hud.HUDElementSkinData
import com.osudroid.ui.v2.hud.HUDSkinData
import com.osudroid.ui.v2.hud.elements.HUDAccuracyCounter
import com.osudroid.ui.v2.hud.elements.HUDComboCounter
import com.osudroid.ui.v2.hud.elements.HUDHealthBar
import com.osudroid.ui.v2.hud.elements.HUDLinearSongProgress
import com.osudroid.ui.v2.hud.elements.HUDPieSongProgress
import com.osudroid.ui.v2.hud.elements.HUDScoreCounter
import com.osudroid.ui.v2.hud.elements.HUDSongProgress
import com.osudroid.utils.updateThread
import com.reco1l.andengine.UIScene
import com.reco1l.andengine.Anchor
import com.reco1l.andengine.box
import com.reco1l.andengine.circle
import com.reco1l.andengine.component.UIComponent
import com.reco1l.andengine.component.UIComponent.Companion.FillParent
import com.reco1l.andengine.container
import com.reco1l.andengine.container.UIContainer
import com.reco1l.andengine.shape.PaintStyle
import com.reco1l.andengine.shape.UIBox
import com.reco1l.andengine.shape.UICircle
import com.reco1l.andengine.sprite
import com.reco1l.andengine.sprite.ScaleType
import com.reco1l.andengine.sprite.UIAnimatedSprite
import com.reco1l.andengine.text
import com.reco1l.andengine.text.UIText
import com.reco1l.andengine.textButton
import com.reco1l.andengine.ui.UITextButton
import com.reco1l.framework.Color4
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
import ru.nsu.ccfit.zuev.skins.OsuSkin
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.reflect.KClass

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
    private val laneTop = screenHeight * 0.29f
    private val laneHeight = screenHeight * 0.25f
    private val laneBottom = laneTop + laneHeight
    private val laneY = laneTop + laneHeight / 2f
    private val leftPanelWidth = screenWidth * 0.14f
    private val targetX = screenWidth * 0.21f
    private val spawnX = screenWidth + 80f
    private val normalNoteDiameter = laneHeight * 0.52f
    private val bigNoteDiameter = laneHeight * 0.78f

    private val playfield = UIContainer()
    private val hudLayer = UIContainer()
    private lateinit var scoreCounter: HUDScoreCounter
    private lateinit var accuracyCounter: HUDAccuracyCounter
    private lateinit var comboCounter: HUDComboCounter
    private lateinit var healthBar: HUDHealthBar
    private lateinit var songProgress: HUDSongProgress
    private lateinit var skipButton: UIAnimatedSprite
    private lateinit var inputFlash: UICircle
    private lateinit var songIntro: UIContainer
    private val judgementText: UIText
    private val loadingText: UIText
    private val modal: UIContainer
    private lateinit var modalTitle: UIText
    private lateinit var primaryButton: UITextButton
    private lateinit var restartButton: UITextButton

    private var objects = emptyList<TaikoObject>()
    private var preempt = 1650.0
    private var greatWindow = 35.0
    private var goodWindow = 80.0
    private var isReady = false
    private var isPaused = false
    private var isFinished = false
    private var judgementTimeRemaining = 0f
    private var inputFlashTimeRemaining = 0f
    private var introShownAt = System.currentTimeMillis()
    private var isLoaded = false
    private var songHasStarted = false
    private var leadInRemaining = 0.0
    private var firstObjectStartTime = 0.0
    private var lastObjectEndTime = 0.0
    private var skipTargetTime = 0.0

    private var score = 0L
    private var combo = 0
    private var maxCombo = 0
    private var greatCount = 0
    private var goodCount = 0
    private var missCount = 0
    private var rollHits = 0
    private var health = 0.5f

    init {
        // Keep the selected beatmap background visible below the Taiko lane.
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
            alpha = (1f - Config.getBackgroundBrightness()).coerceIn(0f, 1f)
        }

        // Warm header and dark upper-third lane based on the stable Taiko layout.
        box {
            width = FillParent
            height = laneTop
            color = Color4(0xFFF58B2A)
            alpha = 0.92f
        }

        repeat(12) { index ->
            val size = 10f + (index % 3) * 6f
            circle {
                x = leftPanelWidth + 35f + index * ((screenWidth - leftPanelWidth - 70f) / 12f)
                y = 22f + (index % 4) * 20f
                width = size
                height = size
                color = Color4.White
                alpha = 0.18f
            }
        }

        text {
            x = leftPanelWidth + 22f
            y = laneTop - 44f
            font = resources.getFont("middleFont")
            color = Color4.White
            text = "osu!taiko (BETA)"
        }

        box {
            x = 0f
            y = laneTop
            width = FillParent
            height = laneHeight
            color = Color4(0xFF121218)
            alpha = 0.97f
        }

        box {
            x = 0f
            y = laneTop
            width = FillParent
            height = 3f
            color = Color4(0xFF44444F)
        }

        box {
            x = 0f
            y = laneBottom - 3f
            width = FillParent
            height = 3f
            color = Color4(0xFF050507)
        }

        // Left drum panel. It is decorative only; playable controls remain invisible.
        box {
            x = 0f
            y = laneTop
            width = leftPanelWidth
            height = laneHeight
            color = Color4(0xFFE83E78)
            alpha = 0.96f
        }

        circle {
            val diameter = laneHeight * 0.72f
            x = (leftPanelWidth - diameter) / 2f
            y = laneY - diameter / 2f
            width = diameter
            height = diameter
            color = Color4(0xFFFFF7E8)

            circle {
                x = 6f
                y = 6f
                width = diameter - 12f
                height = diameter - 12f
                color = Color4(0xFF9B8792)
                alpha = 0.35f
                paintStyle = PaintStyle.Outline
                lineWidth = 4f
            }
        }

        text {
            x = 0f
            y = laneY - 13f
            width = leftPanelWidth
            alignment = Anchor.TopCenter
            font = resources.getFont("smallFont")
            color = Color4(0xFF4A3440)
            text = "BETA"
        }

        // Static hit target with a warm hit-flash layer.
        val targetDiameter = laneHeight * 0.76f
        circle {
            x = targetX - targetDiameter / 2f
            y = laneY - targetDiameter / 2f
            width = targetDiameter
            height = targetDiameter
            color = Color4(0xFFFFC13A)
            alpha = 0.34f
            paintStyle = PaintStyle.Outline
            lineWidth = 10f
        }

        circle {
            x = targetX - targetDiameter * 0.36f
            y = laneY - targetDiameter * 0.36f
            width = targetDiameter * 0.72f
            height = targetDiameter * 0.72f
            color = Color4(0xFFFFFFFF)
            alpha = 0.82f
            paintStyle = PaintStyle.Outline
            lineWidth = 4f
        }

        inputFlash = circle {
            x = targetX - targetDiameter * 0.46f
            y = laneY - targetDiameter * 0.46f
            width = targetDiameter * 0.92f
            height = targetDiameter * 0.92f
            color = DON_COLOR
            alpha = 0f
        }

        attachChild(playfield)

        judgementText = text {
            x = targetX - 95f
            y = laneBottom + 10f
            width = 190f
            alignment = Anchor.TopCenter
            font = resources.getFont("middleFont")
            text = ""
        }

        box {
            x = 0f
            y = laneBottom
            width = FillParent
            height = 42f
            color = Color4.Black
            alpha = 0.78f
        }

        text {
            x = leftPanelWidth + 18f
            y = laneBottom + 9f
            width = screenWidth - leftPanelWidth - 36f
            alignment = Anchor.TopRight
            font = resources.getFont("smallFont")
            text = "${beatmapInfo.artistText} - ${beatmapInfo.titleText} [${beatmapInfo.version}]"
        }

        createTaikoHud()

        skipButton = UIAnimatedSprite(
            "play-skip",
            true,
            OsuSkin.get().animationFramerate
        ).apply {
            origin = Anchor.BottomRight
            setPosition(screenWidth, screenHeight)
            alpha = 0.7f
            isVisible = false
        }
        hudLayer.attachChild(skipButton)

        // Reuse the same two-second beatmap intro presentation used by normal gameplay.
        songIntro = container {
            width = FillParent
            height = FillParent

            background = UIBox().apply {
                color = Color4.Black
                alpha = 0.72f
            }

            text {
                x = 60f
                y = screenHeight * 0.34f
                width = screenWidth * 0.7f
                font = resources.getFont("bigFont")
                color = Color4(0xFFFF80AB)
                text = beatmapInfo.titleText
            }

            text {
                x = 60f
                y = screenHeight * 0.34f + 74f
                width = screenWidth * 0.7f
                font = resources.getFont("middleFont")
                color = Color4(0xFFFFB5CC)
                text = beatmapInfo.version
            }

            text {
                x = 60f
                y = screenHeight * 0.34f + 122f
                width = screenWidth * 0.7f
                font = resources.getFont("middleFont")
                text = "by ${beatmapInfo.artistText}"
            }

            text {
                x = 60f
                y = screenHeight - 92f
                font = resources.getFont("smallFont")
                color = Color4(0xFFFF80AB)
                text = "osu!taiko (BETA)  •  Outer quarters: KAT  •  Inner half: DON"
            }

            loadingText = text {
                x = 60f
                y = screenHeight - 54f
                font = resources.getFont("smallFont")
                text = "Loading beatmap…"
            }
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

    private fun createTaikoHud() {
        hudLayer.width = FillParent
        hudLayer.height = FillParent
        attachChild(hudLayer)

        val selectedLayout = OsuSkin.get().hudSkinData
        val defaultLayout = HUDSkinData.Default

        fun dataFor(type: KClass<out HUDElement>): HUDElementSkinData =
            selectedLayout.elements.firstOrNull { it.type == type }
                ?: defaultLayout.elements.first { it.type == type }

        fun attach(element: HUDElement, data: HUDElementSkinData) {
            hudLayer.attachChild(element)
            element.setSkinData(data)
        }

        scoreCounter = HUDScoreCounter()
        accuracyCounter = HUDAccuracyCounter()
        comboCounter = HUDComboCounter()
        healthBar = HUDHealthBar()

        attach(scoreCounter, dataFor(HUDScoreCounter::class))
        attach(accuracyCounter, dataFor(HUDAccuracyCounter::class))
        attach(comboCounter, dataFor(HUDComboCounter::class))
        attach(healthBar, dataFor(HUDHealthBar::class))

        val progressData = selectedLayout.elements.firstOrNull {
            it.type == HUDPieSongProgress::class || it.type == HUDLinearSongProgress::class
        } ?: dataFor(HUDPieSongProgress::class)

        songProgress = if (progressData.type == HUDLinearSongProgress::class) {
            HUDLinearSongProgress()
        } else {
            HUDPieSongProgress()
        }
        attach(songProgress, progressData)

        if (selectedLayout == defaultLayout) {
            accuracyCounter.y += scoreCounter.y + scoreCounter.height
            songProgress.y = accuracyCounter.y + accuracyCounter.transformedHeight / 2f
            songProgress.x = accuracyCounter.x - accuracyCounter.transformedWidth - 18f
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
                if (taikoObjects.isEmpty()) {
                    throw IllegalArgumentException("This osu!taiko beatmap has no playable objects")
                }

                val od = parsed.difficulty.od.toDouble().coerceIn(0.0, 10.0)
                val calculatedGreatWindow = (50.0 - 3.0 * od).coerceAtLeast(20.0)
                val calculatedGoodWindow = (120.0 - 8.0 * od).coerceAtLeast(40.0)
                val firstObjectTime = taikoObjects.first().startTime
                val lastObjectTime = taikoObjects.maxOf { it.endTime }
                val calculatedSkipTarget = (
                    firstObjectTime - max(2000.0, preempt)
                ).coerceAtLeast(0.0)

                if (!songService.preLoad(beatmapInfo.audioPath)) {
                    throw IllegalStateException("Unable to load beatmap audio")
                }

                songService.setSpeed(1f)
                songService.setAdjustPitch(false)
                songService.setVolume(Config.getBgmVolume())
                songService.setGaming(true)
                songService.seekTo(0)

                updateThread {
                    objects = taikoObjects
                    greatWindow = calculatedGreatWindow
                    goodWindow = calculatedGoodWindow
                    firstObjectStartTime = firstObjectTime
                    lastObjectEndTime = lastObjectTime
                    skipTargetTime = calculatedSkipTarget
                    leadInRemaining = parsed.general.audioLeadIn.toDouble().coerceAtLeast(0.0)
                    skipButton.isVisible = calculatedSkipTarget > 1000.0
                    loadingText.text = "Ready"
                    isLoaded = true
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
        if (
            isLoaded &&
            !isReady &&
            !isFinished &&
            System.currentTimeMillis() - introShownAt >= SONG_INTRO_DURATION_MS
        ) {
            beginGameplay()
        }

        if (isReady && !isPaused && !isFinished) {
            if (!songHasStarted) {
                leadInRemaining -= deltaTimeSec * 1000.0

                if (leadInRemaining <= 0.0) {
                    leadInRemaining = 0.0
                    songHasStarted = true
                    songService.play()
                }
            }

            val now = if (songHasStarted) songService.positionPrecise else -leadInRemaining

            processObjects(now)
            updateHud(now, deltaTimeSec)
            updateSkipButton(now)

            if (now > lastObjectEndTime + 1800) {
                finish()
            } else if (songHasStarted && songService.status == Status.STOPPED && now > 0) {
                finish()
            }
        }

        if (judgementTimeRemaining > 0f) {
            judgementTimeRemaining -= deltaTimeSec
            if (judgementTimeRemaining <= 0f) {
                judgementText.text = ""
            }
        }

        if (inputFlashTimeRemaining > 0f) {
            inputFlashTimeRemaining -= deltaTimeSec
            inputFlash.alpha = (inputFlashTimeRemaining / INPUT_FLASH_DURATION).coerceIn(0f, 0.62f)
        } else {
            inputFlash.alpha = 0f
        }

        super.onManagedUpdate(deltaTimeSec)
    }

    private fun beginGameplay() {
        songIntro.isVisible = false
        isReady = true

        if (leadInRemaining <= 0.0) {
            songHasStarted = true
            songService.play()
        }
    }

    private fun updateSkipButton(now: Double) {
        if (skipButton.isVisible && (now >= skipTargetTime - 1000.0 || now >= firstObjectStartTime)) {
            skipButton.isVisible = false
        }
    }

    private fun skipIntro() {
        if (!skipButton.isVisible || skipTargetTime <= 0.0) {
            return
        }

        leadInRemaining = 0.0
        songHasStarted = true
        songService.seekTo(skipTargetTime.toInt())
        songService.play()
        skipButton.isVisible = false
        resources.getSound("menuhit", false)?.play()
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
        ObjectKind.Don, ObjectKind.Kat -> UICircle().apply {
            val diameter = if (obj.isBig) bigNoteDiameter else normalNoteDiameter
            width = diameter
            height = diameter
            color = Color4.White

            circle {
                val inset = if (obj.isBig) 7f else 5f
                x = inset
                y = inset
                width = diameter - inset * 2f
                height = diameter - inset * 2f
                color = if (obj.kind == ObjectKind.Kat) KAT_COLOR else DON_COLOR
            }

            circle {
                val centerSize = diameter * 0.27f
                x = (diameter - centerSize) / 2f
                y = (diameter - centerSize) / 2f
                width = centerSize
                height = centerSize
                color = Color4.White
                alpha = 0.78f
            }
        }

        ObjectKind.Drumroll, ObjectKind.Denden -> UIBox().apply {
            val durationWidth = max(
                80f,
                ((obj.endTime - obj.startTime) / preempt * (spawnX - targetX)).toFloat()
            )
            width = durationWidth
            height = if (obj.kind == ObjectKind.Denden) laneHeight * 0.54f else laneHeight * 0.34f
            cornerRadius = height / 2f
            color = if (obj.kind == ObjectKind.Denden) Color4(0xFFFFC107) else Color4(0xFFFF7043)
            alpha = 0.88f
        }
    }

    private fun handleTouch(event: TouchEvent): Boolean {
        if (!event.isActionDown || !isReady || isPaused || isFinished) {
            return false
        }

        if (
            skipButton.isVisible &&
            event.x >= screenWidth - SKIP_TOUCH_RADIUS &&
            event.y >= screenHeight - SKIP_TOUCH_RADIUS
        ) {
            skipIntro()
            return true
        }

        // Four invisible full-height controls: outer quarters are Kat, inner quarters are Don.
        val isKat = event.x < screenWidth / 4f || event.x >= screenWidth * 3f / 4f
        val now = if (songHasStarted) songService.positionPrecise else -leadInRemaining
        registerInput(isKat, now)
        return true
    }

    private fun registerInput(isKat: Boolean, now: Double) {
        inputFlash.color = if (isKat) KAT_COLOR else DON_COLOR
        inputFlashTimeRemaining = INPUT_FLASH_DURATION

        val activeRoll = objects.firstOrNull {
            !it.judged &&
                (it.kind == ObjectKind.Drumroll || it.kind == ObjectKind.Denden) &&
                now in it.startTime..it.endTime
        }

        if (activeRoll != null) {
            rollHits++
            score += if (activeRoll.kind == ObjectKind.Denden) 100 else 50
            health = (health + 0.0025f).coerceAtMost(1f)
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
            health = (health + 0.025f * multiplier).coerceAtMost(1f)
            showJudgement("GREAT", Color4(0xFFFFD54F))
        } else {
            goodCount++
            combo++
            score += (100L + combo * 4L) * multiplier
            health = (health + 0.0125f * multiplier).coerceAtMost(1f)
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
        health = (health - 0.07f).coerceAtLeast(0f)
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

    private fun calculateAccuracy(): Double {
        val judged = greatCount + goodCount + missCount
        return if (judged == 0) 1.0 else (greatCount * 2.0 + goodCount) / (judged * 2.0)
    }

    private fun updateHud(now: Double, deltaTimeSec: Float) {
        scoreCounter.setScore(score)
        accuracyCounter.setAccuracy(calculateAccuracy().toFloat())
        comboCounter.setCombo(combo)
        healthBar.setHealth(health, deltaTimeSec)

        if (now < firstObjectStartTime) {
            val progress = if (firstObjectStartTime <= 0.0) {
                1f
            } else {
                (now.coerceAtLeast(0.0) / firstObjectStartTime).toFloat().coerceIn(0f, 1f)
            }
            songProgress.setProgress(progress, true)
        } else {
            val duration = (lastObjectEndTime - firstObjectStartTime).coerceAtLeast(1.0)
            val progress = ((now - firstObjectStartTime) / duration).toFloat().coerceIn(0f, 1f)
            songProgress.setProgress(progress, false)
        }
    }

    fun pause() {
        if (!isReady || isPaused || isFinished) {
            return
        }

        isPaused = true
        if (songHasStarted) {
            songService.pause()
        }
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
        if (!isReady && !isFinished) {
            exitToSongMenu()
            return
        }

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
        if (songHasStarted) {
            songService.play()
        }
    }

    private fun finish() {
        if (isFinished) {
            return
        }

        isFinished = true
        if (songHasStarted) {
            songService.pause()
        }
        updateHud(lastObjectEndTime, 0.2f)

        val accuracy = calculateAccuracy() * 100

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
        private const val SONG_INTRO_DURATION_MS = 2000L
        private const val INPUT_FLASH_DURATION = 0.12f
        private const val SKIP_TOUCH_RADIUS = 250f

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
