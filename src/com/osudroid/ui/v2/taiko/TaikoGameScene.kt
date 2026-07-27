package com.osudroid.ui.v2.taiko

import android.util.Log
import com.osudroid.GameMode
import com.osudroid.beatmaps.constants.SampleBank
import com.osudroid.beatmaps.hitobjects.BankHitSampleInfo
import com.osudroid.beatmaps.hitobjects.HitCircle
import com.osudroid.beatmaps.hitobjects.HitObject
import com.osudroid.beatmaps.hitobjects.HitSampleInfo
import com.osudroid.beatmaps.hitobjects.Slider
import com.osudroid.beatmaps.hitobjects.Spinner
import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.beatmaps.sections.BeatmapControlPoints
import com.osudroid.beatmaps.sections.BeatmapDifficulty
import com.osudroid.data.BeatmapInfo
import com.osudroid.mods.ModAutoplay
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
import com.osudroid.utils.ModHashMap
import com.osudroid.utils.updateThread
import com.reco1l.andengine.UIScene
import com.reco1l.andengine.Anchor
import com.reco1l.andengine.box
import com.reco1l.andengine.circle
import com.reco1l.andengine.component.UIComponent
import com.reco1l.andengine.component.UIComponent.Companion.FillParent
import com.reco1l.andengine.component.transformedHeight
import com.reco1l.andengine.component.transformedWidth
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
import ru.nsu.ccfit.zuev.osu.scoring.ScoringScene
import ru.nsu.ccfit.zuev.osu.scoring.StatisticV2
import com.osudroid.ui.v2.GameLoaderScene
import com.osudroid.ui.v2.hud.GameplayHUD
import ru.nsu.ccfit.zuev.osu.game.GameScene
import java.util.concurrent.CompletableFuture
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
class TaikoGameScene private constructor(
    private val beatmapInfo: BeatmapInfo,
    private val mods: ModHashMap
) : UIScene() {
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
        /** Scroll speed in pixels per millisecond, resolved from the map's timing and SV. */
        var velocity: Double = 0.0,
        /** Time in milliseconds this object takes to travel from spawn to the hit target. */
        var preempt: Double = 1650.0,
        var judged: Boolean = false,
        var entity: UIComponent? = null
    )

    /**
     * A note entity that has been judged and is now animating out. Keeping it around briefly
     * avoids notes popping out of existence the instant they are hit or missed.
     */
    private class DecayingEntity(
        val entity: UIComponent,
        val duration: Float,
        var remaining: Float,
        val velocityX: Float,
        val velocityY: Float
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
    private val targetDiameter = laneHeight * 0.76f
    private val judgementBaseY = laneBottom + 10f

    /** Distance a note travels from spawn to the judgement circle. */
    private val travelDistance = spawnX - targetX

    private val playfield = UIContainer()
    private val hudLayer = UIContainer()
    private lateinit var scoreCounter: HUDScoreCounter
    private lateinit var accuracyCounter: HUDAccuracyCounter
    private lateinit var comboCounter: HUDComboCounter
    private lateinit var healthBar: HUDHealthBar
    private lateinit var songProgress: HUDSongProgress
    private lateinit var skipButton: UIAnimatedSprite
    private lateinit var inputFlash: UICircle
    private lateinit var hitExplosion: UICircle
    private lateinit var songIntro: UIContainer
    private val judgementText: UIText
    private lateinit var loadingText: UIText
    private val modal: UIContainer
    private lateinit var modalTitle: UIText
    private lateinit var primaryButton: UITextButton
    private lateinit var restartButton: UITextButton

    private var objects = emptyList<TaikoObject>()
    /** Notes before this index are all judged, so scanning can start here. */
    private var firstActiveIndex = 0
    private val decayingEntities = mutableListOf<DecayingEntity>()
    private var greatWindow = 35.0
    private var goodWindow = 80.0
    private var missWindow = 95.0
    private var isReady = false
    private var isPaused = false
    private var isFinished = false
    private var judgementTimeRemaining = 0f
    private var inputFlashTimeRemaining = 0f
    private var explosionTimeRemaining = 0f
    private var explosionBaseDiameter = 0f
    private var introShownAt = System.currentTimeMillis()
    private var isBeatmapLoaded = false
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
    private var health = 0f
    private val isAutoPlay = mods.contains(ModAutoplay::class.java)

    /** Adapter bridging TaikoGameScene to GameScene for ScoringScene/GameLoaderScene. */
    private var gameAdapter: TaikoGameSceneAdapter? = null

    /** ScoringScene instance wired to the taiko adapter. */
    private var scoringScene: ScoringScene? = null

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
            text = if (isAutoPlay) "Watching osu!taiko (BETA)" else "osu!taiko (BETA)"
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
            y = targetDiameter.let { laneY - it * 0.36f }
            width = targetDiameter * 0.72f
            height = targetDiameter * 0.72f
            color = Color4(0xFFFFFFFF)
            alpha = 0.82f
            paintStyle = PaintStyle.Outline
            lineWidth = 4f
        }

        inputFlash = circle {
            x = targetX - targetDiameter * 0.46f
            y = targetDiameter.let { laneY - it * 0.46f }
            width = targetDiameter * 0.92f
            height = targetDiameter * 0.92f
            color = DON_COLOR
            alpha = 0f
        }

        // Hit explosion. Unlike the note itself this stays pinned to the judgement circle,
        // matching stable osu!taiko where the burst never drifts with the note.
        hitExplosion = circle {
            x = targetX - normalNoteDiameter / 2f
            y = laneY - normalNoteDiameter / 2f
            width = normalNoteDiameter
            height = normalNoteDiameter
            color = DON_COLOR
            alpha = 0f
            paintStyle = PaintStyle.Outline
            lineWidth = 6f
        }

        attachChild(playfield)

        judgementText = text {
            x = targetX - 95f
            y = judgementBaseY
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

        // Fallback intro presentation. Normal flow uses the standard GameLoaderScene instead.
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

        if (!isAutoPlay) {
            setOnSceneTouchListener { _, event -> handleTouch(event) }
        }
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

    /**
     * Creates the GameScene adapter and GameLoaderScene (the standard beatmap loading/intro
     * screen), then starts async beatmap loading.
     */
    fun beginLoadingWithLoader() {
        val adapter = TaikoGameSceneAdapter()
        gameAdapter = adapter

        val loader = GameLoaderScene(adapter, beatmapInfo, mods, false)
        global.engine.scene = loader

        beginLoading()
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

                val taikoObjects = parsed.hitObjects.objects.map {
                    it.toTaikoObject(parsed.controlPoints, parsed.difficulty)
                }
                if (taikoObjects.isEmpty()) {
                    throw IllegalArgumentException("This osu!taiko beatmap has no playable objects")
                }

                // osu!taiko hit windows. Unlike the previous approximation these keep GREAT, OK
                // and MISS separate, so a late tap inside the miss window is still judged rather
                // than the note silently disappearing at the OK boundary.
                val od = parsed.difficulty.od.toDouble().coerceIn(0.0, 10.0)
                val calculatedGreatWindow = (50.0 - 3.0 * od).coerceAtLeast(20.0)
                val calculatedGoodWindow = if (od <= 5.0) 120.0 - 8.0 * od else 110.0 - 6.0 * od
                val calculatedMissWindow = if (od <= 5.0) 135.0 - 8.0 * od else 120.0 - 5.0 * od

                val firstObject = taikoObjects.first()
                val firstObjectTime = firstObject.startTime
                val lastObjectTime = taikoObjects.maxOf { it.endTime }
                val calculatedSkipTarget = (
                    firstObjectTime - max(2000.0, firstObject.preempt)
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
                    firstActiveIndex = 0
                    greatWindow = calculatedGreatWindow
                    goodWindow = calculatedGoodWindow
                    missWindow = calculatedMissWindow
                    firstObjectStartTime = firstObjectTime
                    lastObjectEndTime = lastObjectTime
                    skipTargetTime = calculatedSkipTarget
                    leadInRemaining = parsed.general.audioLeadIn.toDouble().coerceAtLeast(0.0)
                    skipButton.isVisible = calculatedSkipTarget > 1000.0
                    loadingText.text = "Ready"
                    isBeatmapLoaded = true
                    gameAdapter?.isReadyToStart = true
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

    private fun HitObject.toTaikoObject(
        controlPoints: BeatmapControlPoints,
        difficulty: BeatmapDifficulty
    ): TaikoObject {
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

        // Resolve scroll speed per object rather than using one fixed preempt for the whole map.
        // This is what makes BPM changes and mid-map slider velocity changes read correctly.
        val timingPoint = controlPoints.timing.controlPointAt(startTime)
        val difficultyPoint = controlPoints.difficulty.controlPointAt(startTime)

        // osu!taiko scrolls 175 pixels per beat per unit of slider velocity, referenced against
        // an 800px wide playfield. The common SV 1.4 therefore gives the familiar 245 px/beat.
        val pxPerBeat = TAIKO_SCROLL_PX_PER_BEAT *
            difficulty.sliderMultiplier *
            difficultyPoint.speedMultiplier *
            (screenWidth / REFERENCE_PLAYFIELD_WIDTH)

        val velocity = (pxPerBeat / timingPoint.msPerBeat).coerceAtLeast(0.01)

        return TaikoObject(
            kind,
            startTime,
            endTime,
            isBig,
            samples,
            velocity = velocity,
            preempt = (travelDistance / velocity).coerceIn(MIN_PREEMPT, MAX_PREEMPT)
        )
    }

    override fun onManagedUpdate(deltaTimeSec: Float) {
        if (
            isBeatmapLoaded &&
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

            if (isAutoPlay) {
                processAutoPlay(now)
            }

            if (now > lastObjectEndTime + 1800) {
                finish()
            } else if (songHasStarted && songService.status == Status.STOPPED && now > 0) {
                finish()
            }
        }

        if (!isPaused) {
            updateDecayingEntities(deltaTimeSec)
        }

        // Judgement text drifts upward as it fades instead of blinking out.
        if (judgementTimeRemaining > 0f) {
            judgementTimeRemaining -= deltaTimeSec

            if (judgementTimeRemaining <= 0f) {
                judgementText.text = ""
                judgementText.y = judgementBaseY
                judgementText.alpha = 1f
            } else {
                val progress = 1f - (judgementTimeRemaining / JUDGEMENT_DURATION).coerceIn(0f, 1f)
                judgementText.y = judgementBaseY - JUDGEMENT_DRIFT * progress
                judgementText.alpha = (1f - progress).coerceIn(0f, 1f)
            }
        }

        if (inputFlashTimeRemaining > 0f) {
            inputFlashTimeRemaining -= deltaTimeSec
            inputFlash.alpha = (inputFlashTimeRemaining / INPUT_FLASH_DURATION).coerceIn(0f, 0.62f)
        } else {
            inputFlash.alpha = 0f
        }

        // The explosion expands from the judgement circle and stays centred on it.
        if (explosionTimeRemaining > 0f) {
            explosionTimeRemaining -= deltaTimeSec

            val progress = 1f - (explosionTimeRemaining / EXPLOSION_DURATION).coerceIn(0f, 1f)
            val diameter = explosionBaseDiameter * (1f + EXPLOSION_GROWTH * progress)

            hitExplosion.width = diameter
            hitExplosion.height = diameter
            hitExplosion.x = targetX - diameter / 2f
            hitExplosion.y = laneY - diameter / 2f
            hitExplosion.alpha = (EXPLOSION_ALPHA * (1f - progress)).coerceIn(0f, 1f)
        } else {
            hitExplosion.alpha = 0f
        }

        super.onManagedUpdate(deltaTimeSec)
    }

    private fun updateDecayingEntities(deltaTimeSec: Float) {
        if (decayingEntities.isEmpty()) {
            return
        }

        val iterator = decayingEntities.iterator()
        while (iterator.hasNext()) {
            val decaying = iterator.next()
            decaying.remaining -= deltaTimeSec

            if (decaying.remaining <= 0f) {
                decaying.entity.detachSelf()
                iterator.remove()
                continue
            }

            val progress = 1f - (decaying.remaining / decaying.duration).coerceIn(0f, 1f)
            decaying.entity.x += decaying.velocityX * deltaTimeSec
            decaying.entity.y += decaying.velocityY * deltaTimeSec
            decaying.entity.alpha = (1f - progress).coerceIn(0f, 1f)
        }
    }

    private fun beginGameplay() {
        if (isReady) return
        songIntro.isVisible = false
        global.engine.scene = this
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

    /** Advances the scan cursor past the leading run of already-judged objects. */
    private fun advanceActiveIndex() {
        while (firstActiveIndex < objects.size && objects[firstActiveIndex].judged) {
            firstActiveIndex++
        }
    }

    private fun processObjects(now: Double) {
        advanceActiveIndex()

        for (index in firstActiveIndex until objects.size) {
            val obj = objects[index]

            if (obj.judged) {
                continue
            }

            if (obj.entity == null && now >= obj.startTime - obj.preempt && now <= obj.endTime + missWindow) {
                val entity = createObjectEntity(obj)
                obj.entity = entity
                playfield.attachChild(entity)
            }

            obj.entity?.let { entity ->
                val x = targetX + ((obj.startTime - now) * obj.velocity).toFloat()
                entity.x = x - entity.width / 2f
                entity.y = laneY - entity.height / 2f
            }

            when (obj.kind) {
                ObjectKind.Don, ObjectKind.Kat -> {
                    if (now - obj.startTime > missWindow) {
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

    private fun processAutoPlay(now: Double) {
        advanceActiveIndex()

        // Auto-play Don/Kat notes with perfect timing.
        for (index in firstActiveIndex until objects.size) {
            val obj = objects[index]

            if (obj.judged) {
                continue
            }

            when (obj.kind) {
                ObjectKind.Don, ObjectKind.Kat -> {
                    // Hit the note exactly at its start time.
                    if (now >= obj.startTime) {
                        autoHitNote(obj, obj.kind == ObjectKind.Kat)
                    }
                }

                ObjectKind.Drumroll -> {
                    // For drumrolls, tap every 100ms to simulate active rolling.
                    if (now in obj.startTime..obj.endTime) {
                        autoHitRoll(now)
                    }
                }

                ObjectKind.Denden -> {
                    // For denden (spinner), tap every 50ms for higher score.
                    if (now in obj.startTime..obj.endTime) {
                        autoHitDenden(now)
                    }
                }
            }
        }
    }

    private var lastAutoRollTime = 0.0
    private var lastAutoDendenTime = 0.0

    private fun autoHitNote(obj: TaikoObject, isKat: Boolean) {
        inputFlash.color = if (isKat) KAT_COLOR else DON_COLOR
        inputFlashTimeRemaining = INPUT_FLASH_DURATION

        val multiplier = if (obj.isBig) 2 else 1
        greatCount++
        combo++
        score += (300L + combo * 12L) * multiplier
        health = (health + 0.025f * multiplier).coerceAtMost(1f)
        showJudgement("GREAT", Color4(0xFFFFD54F))
        triggerHitExplosion(isKat, obj.isBig)
        maxCombo = max(maxCombo, combo)

        playSamples(obj)
        releaseHit(obj)
    }

    private fun autoHitRoll(now: Double) {
        // Tap every 100ms during a drumroll. Note that positions here are milliseconds.
        if (now - lastAutoRollTime < AUTO_ROLL_INTERVAL) {
            return
        }
        lastAutoRollTime = now

        rollHits++
        score += 50
        health = (health + 0.0025f).coerceAtMost(1f)

        inputFlash.color = DON_COLOR
        inputFlashTimeRemaining = INPUT_FLASH_DURATION
        showJudgement("ROLL", Color4(0xFFFFC107))
    }

    private fun autoHitDenden(now: Double) {
        // Tap every 50ms during a denden for higher score.
        if (now - lastAutoDendenTime < AUTO_DENDEN_INTERVAL) {
            return
        }
        lastAutoDendenTime = now

        rollHits++
        score += 100
        health = (health + 0.0025f).coerceAtMost(1f)

        inputFlash.color = Color4(0xFFFFC107)
        inputFlashTimeRemaining = INPUT_FLASH_DURATION
        showJudgement("DEN!", Color4(0xFFFFC107))
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
            // Length follows the object's own scroll speed so the body matches its duration.
            val durationWidth = max(
                80f,
                ((obj.endTime - obj.startTime) * obj.velocity).toFloat()
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
            triggerHitExplosion(isKat, false)
            playInputSound(isKat, now)
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
            playInputSound(isKat, now)
            return
        }

        val expectsKat = candidate.kind == ObjectKind.Kat
        if (expectsKat != isKat) {
            playInputSound(isKat, now)
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

        triggerHitExplosion(isKat, candidate.isBig)
        maxCombo = max(maxCombo, combo)
        playSamples(candidate)
        releaseHit(candidate)
    }

    /**
     * The sample names a note plays, chosen by note type rather than by whichever addition the
     * mapper happened to use.
     *
     * - Don: `hitnormal`
     * - Kat: `hitclap`
     * - Big don: `hitnormal` with `hitfinish`
     * - Big kat: `hitwhistle` with `hitfinish`
     */
    private fun sampleNamesFor(obj: TaikoObject): List<String> = when {
        obj.kind == ObjectKind.Kat && obj.isBig ->
            listOf(BankHitSampleInfo.HIT_WHISTLE, BankHitSampleInfo.HIT_FINISH)

        obj.kind == ObjectKind.Kat -> listOf(BankHitSampleInfo.HIT_CLAP)

        obj.isBig -> listOf(BankHitSampleInfo.HIT_NORMAL, BankHitSampleInfo.HIT_FINISH)

        else -> listOf(BankHitSampleInfo.HIT_NORMAL)
    }

    /**
     * Returns the samples osu!taiko actually plays for [obj], rebuilt from [sampleNamesFor].
     *
     * One of the object's own bank samples is used as the template, so the bank, custom sample
     * index and volume the mapper set all carry over untouched and only the sample name is
     * replaced.
     */
    private fun playableSamples(obj: TaikoObject): List<HitSampleInfo> {
        if (obj.kind != ObjectKind.Don && obj.kind != ObjectKind.Kat) {
            return obj.samples
        }

        val bankSamples = obj.samples.filterIsInstance<BankHitSampleInfo>()

        // Prefer the base sample as the template: it is the one that always carries the object's
        // own bank rather than an addition bank.
        val template = bankSamples.firstOrNull { it.name == BankHitSampleInfo.HIT_NORMAL }
            ?: bankSamples.firstOrNull()
            // Never go silent: an object with no bank sample at all keeps what it had.
            ?: return obj.samples

        val fileSamples = obj.samples.filter { it !is BankHitSampleInfo }

        return sampleNamesFor(obj).map { template.copy(name = it) } + fileSamples
    }

    private fun playSamples(obj: TaikoObject) {
        playableSamples(obj).forEach { sample ->
            com.osudroid.game.GameplayHitSampleInfo.obtain().also {
                it.init(sample)
                it.play()
                it.release()
            }
        }
    }

    /** The sample bank an object is hitsounded with, defaulting to the normal bank. */
    private fun bankOf(obj: TaikoObject): SampleBank =
        obj.samples.filterIsInstance<BankHitSampleInfo>().firstOrNull()?.bank ?: SampleBank.Normal

    /**
     * The sample bank in effect at [now], taken from the object closest in time. Taps that do not
     * land on a note then follow the surrounding hitsounding instead of always using the normal
     * bank.
     */
    private fun activeBank(now: Double): SampleBank {
        var closest: TaikoObject? = null
        var closestDistance = Double.MAX_VALUE

        // Objects are ordered by time, so the search can stop as soon as it starts moving away.
        for (index in (firstActiveIndex - 1).coerceAtLeast(0) until objects.size) {
            val obj = objects[index]
            val distance = abs(now - obj.startTime)

            if (distance < closestDistance) {
                closestDistance = distance
                closest = obj
            } else if (obj.startTime > now) {
                break
            }
        }

        return closest?.let { bankOf(it) } ?: SampleBank.Normal
    }

    /**
     * Plays the raw don/kat tap feedback for the sample bank in effect at [now].
     *
     * Normal and soft banks come from the taiko sample banks in `assets/sfx` and never fall back
     * to the osu!standard samples, so a missing taiko sound stays silent. Drum banks use the
     * osu!standard drum samples.
     */
    private fun playInputSound(isKat: Boolean, now: Double) {
        val name = if (isKat) BankHitSampleInfo.HIT_CLAP else BankHitSampleInfo.HIT_NORMAL

        val lookupNames = when (activeBank(now)) {
            SampleBank.Drum -> listOf("${SampleBank.Drum.prefix}-$name", name)
            SampleBank.Soft -> listOf("${TaikoHitSounds.PREFIX}${SampleBank.Soft.prefix}-$name")
            else -> listOf("${TaikoHitSounds.PREFIX}${SampleBank.Normal.prefix}-$name")
        }

        for (lookupName in lookupNames) {
            val sound = resources.getSound(lookupName, false)

            if (sound != null) {
                sound.play()
                return
            }
        }
    }

    private fun registerMiss(obj: TaikoObject) {
        missCount++
        combo = 0
        health = (health - 0.07f).coerceAtLeast(0f)
        showJudgement("MISS", Color4(0xFFB0BEC5))
        releaseMiss(obj)
    }

    /**
     * Fires the burst at the judgement circle. The size follows the note that was hit, but the
     * position never does, which is what keeps the feedback readable at high scroll speeds.
     */
    private fun triggerHitExplosion(isKat: Boolean, isBig: Boolean) {
        explosionBaseDiameter = if (isBig) bigNoteDiameter else normalNoteDiameter
        hitExplosion.color = if (isKat) KAT_COLOR else DON_COLOR
        explosionTimeRemaining = EXPLOSION_DURATION
    }

    /** Judged as a hit: the note drifts up and away toward the health bar while fading. */
    private fun releaseHit(obj: TaikoObject) {
        obj.judged = true
        obj.entity?.let { entity ->
            decayingEntities.add(
                DecayingEntity(
                    entity,
                    HIT_DECAY_DURATION,
                    HIT_DECAY_DURATION,
                    HIT_DECAY_VELOCITY_X,
                    HIT_DECAY_VELOCITY_Y
                )
            )
        }
        obj.entity = null
    }

    /** Judged as a miss: the note keeps travelling at its own speed and fades out. */
    private fun releaseMiss(obj: TaikoObject) {
        obj.judged = true
        obj.entity?.let { entity ->
            decayingEntities.add(
                DecayingEntity(
                    entity,
                    MISS_DECAY_DURATION,
                    MISS_DECAY_DURATION,
                    -(obj.velocity * 1000.0).toFloat(),
                    0f
                )
            )
        }
        obj.entity = null
    }

    private fun expire(obj: TaikoObject) {
        obj.judged = true
        obj.entity?.detachSelf()
        obj.entity = null
    }

    private fun showJudgement(text: String, color: Color4) {
        judgementText.text = text
        judgementText.color = color
        judgementText.y = judgementBaseY
        judgementText.alpha = 1f
        judgementTimeRemaining = JUDGEMENT_DURATION
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

        // Build a StatisticV2 from taiko stats so the existing standard results screen can show them.
        val stat = StatisticV2().apply {
            setMod(mods)
            setPlayerName(Config.getOnlineUsername())
            setTime(System.currentTimeMillis())
            setHit300(greatCount)
            setHit100(goodCount)
            setMisses(missCount)
            setScoreMaxCombo(maxCombo)
            setTotalScore(score)
            setBeatmapNoteCount(greatCount + goodCount + missCount)
            setBeatmapMaxCombo(greatCount + goodCount + missCount)
            setDiffModifier(1f)
            calculateModScoreMultiplier(null)
        }

        // Use the existing ScoringScene (results screen) instead of a placeholder modal.
        // The taiko adapter is passed so the retry button restarts taiko.
        val adapter = gameAdapter ?: TaikoGameSceneAdapter().also { gameAdapter = it }
        val scoring = scoringScene ?: ScoringScene(
            global.engine,
            adapter,
            global.songMenu
        ).also { scoringScene = it }

        // beatmapInfo is passed so the results screen shows beatmap info and the retry button.
        // mapMD5 is null so the MD5 equality check fails -> the score is NOT saved to the database.
        scoring.load(stat, beatmapInfo, songService, null, null, null)
        global.engine.scene = scoring.scene
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
        start(beatmapInfo, mods)
    }

    /** Cancels async beatmap loading, used by GameLoaderScene's back button via the adapter. */
    fun cancelLoading() {
        loadingJob?.cancel()
        loadingJob = null
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
        decayingEntities.clear()
        songService.stop()
        songService.setGaming(false)
        BeatmapSkinManager.setSkinEnabled(false)
        BeatmapSkinManager.getInstance().clearSkin()
    }

    /**
     * A thin adapter that lets the existing standard GameLoaderScene and ScoringScene work with
     * TaikoGameScene without requiring TaikoGameScene to extend GameScene.
     *
     * This is an inner class, so it implicitly holds a reference to the enclosing TaikoGameScene
     * and takes no constructor arguments.
     *
     * - [start] is called by GameLoaderScene when loading completes; it switches the engine scene
     *   to the taiko scene and begins gameplay.
     * - [cancelLoading] is called by GameLoaderScene when the user presses back.
     * - [startGame] is called by ScoringScene's retry button; it restarts the taiko game.
     * - [loadStoryboard] and [loadVideo] are no-ops: taiko beta has no storyboard or video.
     */
    inner class TaikoGameSceneAdapter : GameScene(global.engine) {
        init {
            // GameLoaderScene accesses gameScene.hud for the transition animation.
            // A fresh empty GameplayHUD is safe -- it is never attached or updated in taiko mode.
            hud = GameplayHUD()
            isReadyToStart = false
        }

        override fun start() {
            global.engine.scene = this@TaikoGameScene
            beginGameplay()
        }

        override fun cancelLoading(): CompletableFuture<Unit> {
            this@TaikoGameScene.cancelLoading()
            return CompletableFuture.completedFuture(Unit)
        }

        override fun startGame(beatmapInfo: BeatmapInfo?, replayFile: String?, mods: ModHashMap?) {
            // Called by ScoringScene's retry button -- restart the taiko game.
            restart()
        }

        override fun loadStoryboard(beatmapInfo: BeatmapInfo?) {
            // No-op: taiko beta does not support storyboards.
        }

        override fun loadVideo(beatmapInfo: BeatmapInfo?) {
            // No-op: taiko beta does not support video backgrounds.
        }
    }

    companion object {
        private val DON_COLOR = Color4(0xFFEF5350)
        private val KAT_COLOR = Color4(0xFF42A5F5)
        private const val SONG_INTRO_DURATION_MS = 2000L
        private const val INPUT_FLASH_DURATION = 0.12f
        private const val SKIP_TOUCH_RADIUS = 250f

        /** Pixels travelled per beat at slider velocity 1.0, on an 800px reference playfield. */
        private const val TAIKO_SCROLL_PX_PER_BEAT = 175.0
        private const val REFERENCE_PLAYFIELD_WIDTH = 800f

        /** Clamps for extreme slider velocities so notes never spawn absurdly early or late. */
        private const val MIN_PREEMPT = 200.0
        private const val MAX_PREEMPT = 6000.0

        private const val EXPLOSION_DURATION = 0.12f
        private const val EXPLOSION_GROWTH = 0.6f
        private const val EXPLOSION_ALPHA = 0.85f

        private const val JUDGEMENT_DURATION = 0.35f
        private const val JUDGEMENT_DRIFT = 18f

        private const val HIT_DECAY_DURATION = 0.4f
        private const val HIT_DECAY_VELOCITY_X = -180f
        private const val HIT_DECAY_VELOCITY_Y = -260f
        private const val MISS_DECAY_DURATION = 0.22f

        /** Autoplay tap intervals, in milliseconds to match song position units. */
        private const val AUTO_ROLL_INTERVAL = 100.0
        private const val AUTO_DENDEN_INTERVAL = 50.0

        @JvmStatic
        @JvmOverloads
        fun start(beatmapInfo: BeatmapInfo, mods: ModHashMap = ModHashMap()) {
            if (beatmapInfo.beatmapMode != 1) {
                ToastLogger.showText("This is not a native osu!taiko beatmap.", true)
                return
            }

            TaikoGameScene(beatmapInfo, mods).also {
                it.beginLoadingWithLoader()
            }
        }
    }
}
