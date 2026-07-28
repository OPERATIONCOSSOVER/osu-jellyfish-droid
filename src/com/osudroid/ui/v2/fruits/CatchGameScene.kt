package com.osudroid.ui.v2.fruits

import android.util.Log
import com.osudroid.GameMode
import com.osudroid.beatmaps.hitobjects.HitCircle
import com.osudroid.beatmaps.hitobjects.HitObject
import com.osudroid.beatmaps.hitobjects.HitSampleInfo
import com.osudroid.beatmaps.hitobjects.Slider
import com.osudroid.beatmaps.hitobjects.Spinner
import com.osudroid.beatmaps.hitobjects.sliderobject.SliderHead
import com.osudroid.beatmaps.hitobjects.sliderobject.SliderRepeat
import com.osudroid.beatmaps.parser.BeatmapParser
import com.osudroid.data.BeatmapInfo
import com.osudroid.mods.ModAutoplay
import com.osudroid.ui.v2.GameLoaderScene
import com.osudroid.ui.v2.hud.GameplayHUD
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
import com.reco1l.andengine.Anchor
import com.reco1l.andengine.UIScene
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
import com.reco1l.andengine.ui.UIMessageDialog
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
import ru.nsu.ccfit.zuev.osu.game.GameScene
import ru.nsu.ccfit.zuev.osu.scoring.ScoringScene
import ru.nsu.ccfit.zuev.osu.scoring.StatisticV2
import ru.nsu.ccfit.zuev.skins.BeatmapSkinManager
import ru.nsu.ccfit.zuev.skins.OsuSkin
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.max
import kotlin.reflect.KClass

/**
 * A deliberately isolated native osu!catch beta scene.
 *
 * As with the osu!taiko beta, scores never enter the osu!droid score/replay database, which keeps
 * existing leaderboards, replays, multiplayer and performance calculations trustworthy while the
 * ruleset is still evolving.
 *
 * This package is named `fruits` rather than `catch` on purpose: `catch` is a Kotlin hard keyword
 * and is not a legal Java package name segment, so a package called `catch` could not be imported
 * from `SongMenu.java` at all.
 */
class CatchGameScene private constructor(
    private val beatmapInfo: BeatmapInfo,
    private val mods: ModHashMap
) : UIScene() {
    private enum class ObjectKind {
        /** A full fruit. Caught fruits build combo and are worth full score. */
        Fruit,

        /** A juice stream droplet. Worth less, and still breaks combo when dropped. */
        Droplet,

        /** A banana from a banana shower. Bonus only: never breaks combo, never affects accuracy. */
        Banana
    }

    private data class CatchObject(
        val kind: ObjectKind,
        val startTime: Double,
        /** Horizontal position in osu!pixels, within the 512 wide catch playfield. */
        val playfieldX: Float,
        /** Time in milliseconds the object takes to fall from the top to the catcher plane. */
        val fallTime: Double,
        val samples: List<HitSampleInfo>,
        var judged: Boolean = false,
        var entity: UIComponent? = null
    )

    /**
     * An entity that has been judged and is now animating out, so caught fruits do not pop out of
     * existence the instant they are collected.
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

    /** Scale factor from the 512 wide catch playfield to screen pixels. */
    private val xScale = screenWidth / CATCH_PLAYFIELD_WIDTH

    /** The vertical line the catcher sits on, and where objects are judged. */
    private val catcherPlaneY = screenHeight * 0.84f

    private val playfield = UIContainer()
    private val hudLayer = UIContainer()
    private lateinit var scoreCounter: HUDScoreCounter
    private lateinit var accuracyCounter: HUDAccuracyCounter
    private lateinit var comboCounter: HUDComboCounter
    private lateinit var healthBar: HUDHealthBar
    private lateinit var songProgress: HUDSongProgress
    private lateinit var skipButton: UIAnimatedSprite
    private lateinit var catcher: UIContainer
    private lateinit var catcherPlate: UIBox
    private lateinit var catcherBody: UICircle
    private lateinit var dashGlow: UIBox
    private val judgementText: UIText
    private lateinit var loadingText: UIText
    private lateinit var songIntro: UIContainer

    private val pauseDialog = UIMessageDialog().apply {
        // A stray tap outside the card must not silently resume a paused run.
        staticBackdrop = true
        title = "osu!catch (BETA)"
        text = "Paused\n\nBeta scores are kept separate and are not uploaded."

        addButton {
            text = "Resume"
            isSelected = true
            onActionUp = { this@CatchGameScene.resume() }
        }

        addButton {
            text = "Restart"
            onActionUp = { this@CatchGameScene.restart() }
        }

        addButton {
            text = "Exit to songs"
            onActionUp = { this@CatchGameScene.exitToSongMenu() }
        }
    }

    private val loadFailedDialog = UIMessageDialog().apply {
        staticBackdrop = true
        title = "osu!catch (BETA)"
        text = "Could not load this osu!catch map."

        addButton {
            text = "Try again"
            isSelected = true
            onActionUp = { this@CatchGameScene.restart() }
        }

        addButton {
            text = "Exit to songs"
            onActionUp = { this@CatchGameScene.exitToSongMenu() }
        }
    }

    private var objects = emptyList<CatchObject>()

    /** Objects before this index are all judged, so scanning can start here. */
    private var firstActiveIndex = 0
    private val decayingEntities = mutableListOf<DecayingEntity>()

    private var isReady = false
    private var isPaused = false
    private var isFinished = false
    private var isBeatmapLoaded = false
    private var songHasStarted = false
    private var judgementTimeRemaining = 0f
    private var introShownAt = System.currentTimeMillis()
    private var leadInRemaining = 0.0
    private var firstObjectStartTime = 0.0
    private var lastObjectEndTime = 0.0
    private var skipTargetTime = 0.0

    // Catcher geometry, resolved from the beatmap's circle size once it has been parsed.
    private var fruitDiameter = 48f
    private var dropletDiameter = 24f
    private var catcherWidth = 160f

    /** Half the width within which the catcher actually catches things. */
    private var catchHalfWidth = 64f

    /** Catcher centre, in screen pixels. */
    private var catcherX = screenWidth / 2f

    /** -1 while moving left, 1 while moving right, 0 while still. */
    private var moveDirection = 0
    private var isDashing = false
    private var lastTapTime = 0L
    private var lastTapDirection = 0

    private var score = 0L
    private var combo = 0
    private var maxCombo = 0
    private var caughtFruits = 0
    private var caughtDroplets = 0
    private var caughtBananas = 0
    private var missCount = 0
    private var health = 0f
    private val isAutoPlay = mods.contains(ModAutoplay::class.java)

    private var gameAdapter: CatchGameSceneAdapter? = null
    private var scoringScene: ScoringScene? = null

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
            alpha = (1f - Config.getBackgroundBrightness()).coerceIn(0f, 1f)
        }

        text {
            x = 26f
            y = 24f
            font = resources.getFont("middleFont")
            color = Color4(0xFF80DEEA)
            text = if (isAutoPlay) "Watching osu!catch (BETA)" else "osu!catch (BETA)"
        }

        attachChild(playfield)

        // The line objects are judged on. Drawn faintly so the catch plane is readable.
        box {
            x = 0f
            y = catcherPlaneY
            width = FillParent
            height = 2f
            color = Color4.White
            alpha = 0.16f
        }

        catcher = container {
            width = catcherWidth
            height = screenHeight * 0.1f

            dashGlow = box {
                width = FillParent
                height = FillParent
                color = Color4(0xFFFFF176)
                alpha = 0f
            }

            catcherBody = circle {
                width = catcherWidth
                height = catcherWidth * 0.5f
                color = Color4(0xFFEC407A)
                alpha = 0.9f
            }

            catcherPlate = box {
                width = catcherWidth
                height = 8f
                cornerRadius = 4f
                color = Color4.White
                alpha = 0.9f
            }
        }

        judgementText = text {
            x = screenWidth / 2f - 110f
            y = catcherPlaneY - 92f
            width = 220f
            alignment = Anchor.TopCenter
            font = resources.getFont("middleFont")
            text = ""
        }

        text {
            x = 26f
            y = screenHeight - 34f
            width = screenWidth - 52f
            alignment = Anchor.TopRight
            font = resources.getFont("smallFont")
            text = "${beatmapInfo.artistText} - ${beatmapInfo.titleText} [${beatmapInfo.version}]"
        }

        createCatchHud()

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

        // Fallback intro presentation. The normal flow uses the standard GameLoaderScene instead.
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
                color = Color4(0xFF80DEEA)
                text = beatmapInfo.titleText
            }

            text {
                x = 60f
                y = screenHeight * 0.34f + 74f
                width = screenWidth * 0.7f
                font = resources.getFont("middleFont")
                color = Color4(0xFFB2EBF2)
                text = beatmapInfo.version
            }

            text {
                x = 60f
                y = screenHeight - 92f
                font = resources.getFont("smallFont")
                color = Color4(0xFF80DEEA)
                text = "osu!catch (BETA)  \u2022  Hold either side to move  \u2022  Double tap to dash"
            }

            loadingText = text {
                x = 60f
                y = screenHeight - 54f
                font = resources.getFont("smallFont")
                text = "Loading beatmap\u2026"
            }
        }

        if (!isAutoPlay) {
            setOnSceneTouchListener { _, event -> handleTouch(event) }
        }
    }

    private fun createCatchHud() {
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

    fun beginLoadingWithLoader() {
        val adapter = CatchGameSceneAdapter()
        gameAdapter = adapter

        val loader = GameLoaderScene(adapter, beatmapInfo, mods, false)
        global.engine.scene = loader

        beginLoading()
    }

    private fun beginLoading() {
        loadingJob = loadingScope.launch {
            try {
                // Parsed as Standard so that positions, stacking and preempt times all come from
                // the existing osu!standard-compatible pipeline. osu!catch reads the same fields.
                val parsed = BeatmapParser(beatmapInfo.path, this, beatmapInfo.md5)
                    .parse(true, GameMode.Standard)

                if (parsed.general.mode != CATCH_BEATMAP_MODE) {
                    throw IllegalArgumentException("Selected beatmap is not a native osu!catch map")
                }

                BeatmapSkinManager.getInstance().loadBeatmapSkin(parsed.beatmapsetPath)

                val catchObjects = parsed.hitObjects.objects
                    .flatMap { it.toCatchObjects() }
                    .sortedBy { it.startTime }

                if (catchObjects.isEmpty()) {
                    throw IllegalArgumentException("This osu!catch beatmap has no playable objects")
                }

                // osu!catch scales both the fruits and the catcher with circle size, using the
                // same curve osu! itself does: CS 5 is neutral, lower is bigger, higher is smaller.
                val cs = parsed.difficulty.gameplayCS.toDouble().coerceIn(0.0, 10.0)
                val csScale = (1.0 - 0.7 * (cs - 5.0) / 5.0).coerceIn(0.25, 1.75).toFloat()

                val calculatedFruitDiameter = FRUIT_OSU_DIAMETER * csScale * xScale
                val calculatedCatcherWidth = CATCHER_OSU_WIDTH * csScale * xScale

                val firstObject = catchObjects.first()
                val lastTime = catchObjects.maxOf { it.startTime }
                val calculatedSkipTarget = (
                    firstObject.startTime - max(2000.0, firstObject.fallTime)
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
                    objects = catchObjects
                    firstActiveIndex = 0
                    fruitDiameter = calculatedFruitDiameter
                    dropletDiameter = calculatedFruitDiameter * DROPLET_SIZE_RATIO
                    catcherWidth = calculatedCatcherWidth
                    catchHalfWidth = calculatedCatcherWidth / 2f * ALLOWED_CATCH_RANGE

                    catcher.width = calculatedCatcherWidth
                    catcherBody.width = calculatedCatcherWidth
                    catcherBody.height = calculatedCatcherWidth * 0.5f
                    catcherPlate.width = calculatedCatcherWidth

                    firstObjectStartTime = firstObject.startTime
                    lastObjectEndTime = lastTime
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

                Log.e("CatchGameScene", "Failed to load osu!catch beta gameplay", e)
                updateThread {
                    val reason = "Could not load this osu!catch map.\n${e.message.orEmpty()}"
                    loadingText.text = reason
                    loadFailedDialog.text = reason
                    loadFailedDialog.show()
                }
            }
        }
    }

    /**
     * Converts a parsed hit object into the objects that actually fall down an osu!catch playfield.
     *
     * A circle is a single fruit. A slider is a juice stream, and rather than re-deriving positions
     * along the curve, this walks the slider's own nested objects, so heads, repeats, ticks and the
     * tail land exactly where the existing pipeline already placed them. A spinner is a banana
     * shower, whose bananas are spread across its duration.
     */
    private fun HitObject.toCatchObjects(): List<CatchObject> = when (this) {
        is HitCircle -> listOf(
            CatchObject(
                ObjectKind.Fruit,
                startTime,
                position.x,
                timePreempt,
                samples
            )
        )

        is Slider -> nestedHitObjects.map { nested ->
            // Heads and repeats are the fruits of a juice stream; ticks and the tail are droplets.
            val kind = if (nested is SliderHead || nested is SliderRepeat) {
                ObjectKind.Fruit
            } else {
                ObjectKind.Droplet
            }

            CatchObject(
                kind,
                nested.startTime,
                nested.position.x,
                nested.timePreempt,
                nested.samples
            )
        }

        is Spinner -> {
            // Bananas are scattered across the shower rather than following a path. The spread is
            // seeded from the start time so a restart produces the very same shower.
            val bananaCount = max(
                1,
                (duration / BANANA_SPACING_MS).toInt()
            )
            var seed = startTime.toLong() or 1L

            (0 until bananaCount).map { index ->
                seed = seed * 6364136223846793005L + 1442695040888963407L
                val unit = ((seed ushr 16) and 0xFFFF).toFloat() / 0xFFFF.toFloat()

                CatchObject(
                    ObjectKind.Banana,
                    startTime + duration * index / bananaCount,
                    unit * CATCH_PLAYFIELD_WIDTH,
                    timePreempt,
                    samples
                )
            }
        }

        else -> emptyList()
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

            if (isAutoPlay) {
                moveCatcherAutomatically(now)
            } else {
                moveCatcher(deltaTimeSec)
            }

            processObjects(now)
            updateHud(now, deltaTimeSec)
            updateSkipButton(now)

            if (now > lastObjectEndTime + 1800) {
                finish()
            } else if (songHasStarted && songService.status == Status.STOPPED && now > 0) {
                finish()
            }
        }

        updateCatcherEntity()

        if (!isPaused) {
            updateDecayingEntities(deltaTimeSec)
        }

        if (judgementTimeRemaining > 0f) {
            judgementTimeRemaining -= deltaTimeSec

            if (judgementTimeRemaining <= 0f) {
                judgementText.text = ""
                judgementText.alpha = 1f
            } else {
                judgementText.alpha =
                    (judgementTimeRemaining / JUDGEMENT_DURATION).coerceIn(0f, 1f)
            }
        }

        dashGlow.alpha = if (isDashing) 0.22f else 0f

        super.onManagedUpdate(deltaTimeSec)
    }

    private fun updateCatcherEntity() {
        catcher.x = catcherX - catcher.width / 2f
        catcher.y = catcherPlaneY - catcher.height * 0.35f
    }

    /** Moves the catcher from held input, clamped to the playfield. */
    private fun moveCatcher(deltaTimeSec: Float) {
        if (moveDirection == 0) {
            return
        }

        val speed = if (isDashing) DASH_SPEED_OSU_PX_PER_MS else BASE_SPEED_OSU_PX_PER_MS
        val distance = (speed * xScale * deltaTimeSec * 1000f) * moveDirection

        catcherX = (catcherX + distance).coerceIn(0f, screenWidth)
    }

    /**
     * Autoplay simply tracks the next object that is still worth catching. Bananas are ignored so
     * that autoplay never abandons a fruit to chase a bonus.
     */
    private fun moveCatcherAutomatically(now: Double) {
        advanceActiveIndex()

        val target = objects
            .asSequence()
            .drop(firstActiveIndex)
            .firstOrNull { !it.judged && it.kind != ObjectKind.Banana && it.startTime >= now }
            ?: objects.asSequence().drop(firstActiveIndex).firstOrNull { !it.judged }
            ?: return

        catcherX = (target.playfieldX * xScale).coerceIn(0f, screenWidth)
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

            val spawnTime = obj.startTime - obj.fallTime

            if (now < spawnTime) {
                // Objects are time ordered, so nothing later has spawned either.
                break
            }

            if (obj.entity == null) {
                val entity = createObjectEntity(obj)
                obj.entity = entity
                playfield.attachChild(entity)
            }

            obj.entity?.let { entity ->
                val progress = ((now - spawnTime) / obj.fallTime).coerceIn(0.0, 1.0).toFloat()
                entity.x = obj.playfieldX * xScale - entity.width / 2f
                entity.y = catcherPlaneY * progress - entity.height / 2f
            }

            if (now >= obj.startTime) {
                judge(obj)
            }
        }
    }

    /**
     * Judges an object as it reaches the catcher plane. osu!catch has no timing windows: an object
     * is either within the catcher's horizontal range or it is not.
     */
    private fun judge(obj: CatchObject) {
        val caught = abs(obj.playfieldX * xScale - catcherX) <= catchHalfWidth

        when (obj.kind) {
            ObjectKind.Fruit -> {
                if (caught) {
                    caughtFruits++
                    combo++
                    maxCombo = max(maxCombo, combo)
                    score += FRUIT_SCORE + combo * COMBO_BONUS
                    health = (health + 0.02f).coerceAtMost(1f)
                    showJudgement("CATCH", Color4(0xFFFFD54F))
                    playSamples(obj)
                    releaseCaught(obj)
                } else {
                    registerMiss(obj)
                }
            }

            ObjectKind.Droplet -> {
                if (caught) {
                    caughtDroplets++
                    combo++
                    maxCombo = max(maxCombo, combo)
                    score += DROPLET_SCORE + combo * COMBO_BONUS
                    health = (health + 0.008f).coerceAtMost(1f)
                    releaseCaught(obj)
                } else {
                    registerMiss(obj)
                }
            }

            ObjectKind.Banana -> {
                // Bananas are bonus only. Dropping one costs nothing at all: no miss, no combo
                // break, and no effect on accuracy.
                if (caught) {
                    caughtBananas++
                    score += BANANA_SCORE
                    health = (health + 0.004f).coerceAtMost(1f)
                    showJudgement("BANANA!", Color4(0xFFFFEE58))
                    playSamples(obj)
                    releaseCaught(obj)
                } else {
                    expire(obj)
                }
            }
        }
    }

    private fun createObjectEntity(obj: CatchObject): UIComponent = when (obj.kind) {
        ObjectKind.Fruit -> UICircle().apply {
            width = fruitDiameter
            height = fruitDiameter
            color = Color4.White

            circle {
                val inset = fruitDiameter * 0.11f
                x = inset
                y = inset
                width = fruitDiameter - inset * 2f
                height = fruitDiameter - inset * 2f
                color = FRUIT_COLOR
            }

            circle {
                val centerSize = fruitDiameter * 0.26f
                x = (fruitDiameter - centerSize) / 2f
                y = (fruitDiameter - centerSize) / 2f
                width = centerSize
                height = centerSize
                color = Color4.White
                alpha = 0.75f
            }
        }

        ObjectKind.Droplet -> UICircle().apply {
            width = dropletDiameter
            height = dropletDiameter
            color = DROPLET_COLOR
            alpha = 0.92f
        }

        ObjectKind.Banana -> UICircle().apply {
            width = fruitDiameter * 0.8f
            height = fruitDiameter * 0.8f
            color = BANANA_COLOR
            paintStyle = PaintStyle.Outline
            lineWidth = max(3f, fruitDiameter * 0.1f)
        }
    }

    private fun handleTouch(event: TouchEvent): Boolean {
        if (!isReady || isPaused || isFinished) {
            return false
        }

        if (event.isActionDown) {
            if (
                skipButton.isVisible &&
                event.x >= screenWidth - SKIP_TOUCH_RADIUS &&
                event.y >= screenHeight - SKIP_TOUCH_RADIUS
            ) {
                skipIntro()
                return true
            }
        }

        if (event.isActionUp || event.isActionCancel) {
            moveDirection = 0
            isDashing = false
            return true
        }

        // Two invisible full-height halves: hold either side to slide the catcher that way.
        val direction = if (event.x < screenWidth / 2f) -1 else 1

        if (event.isActionDown) {
            val tapTime = System.currentTimeMillis()

            // A second tap on the same side within the double tap window starts a dash, which is
            // how osu!catch reaches the far side of the playfield in time.
            isDashing = direction == lastTapDirection &&
                tapTime - lastTapTime <= DOUBLE_TAP_WINDOW_MS

            lastTapTime = tapTime
            lastTapDirection = direction
        }

        moveDirection = direction
        return true
    }

    private fun playSamples(obj: CatchObject) {
        obj.samples.forEach { sample ->
            com.osudroid.game.GameplayHitSampleInfo.obtain().also {
                it.init(sample)
                it.play()
                it.release()
            }
        }
    }

    private fun registerMiss(obj: CatchObject) {
        missCount++
        combo = 0
        health = (health - 0.06f).coerceAtLeast(0f)
        showJudgement("MISS", Color4(0xFFB0BEC5))

        // A dropped object keeps falling past the catcher rather than vanishing on the plane.
        obj.judged = true
        obj.entity?.let { entity ->
            decayingEntities.add(
                DecayingEntity(
                    entity,
                    MISS_DECAY_DURATION,
                    MISS_DECAY_DURATION,
                    0f,
                    MISS_DECAY_VELOCITY_Y
                )
            )
        }
        obj.entity = null
    }

    /** A caught object pops upward off the catcher and fades. */
    private fun releaseCaught(obj: CatchObject) {
        obj.judged = true
        obj.entity?.let { entity ->
            entity.x = catcherX - entity.width / 2f
            entity.y = catcherPlaneY - entity.height / 2f

            decayingEntities.add(
                DecayingEntity(
                    entity,
                    CATCH_DECAY_DURATION,
                    CATCH_DECAY_DURATION,
                    0f,
                    CATCH_DECAY_VELOCITY_Y
                )
            )
        }
        obj.entity = null
    }

    private fun expire(obj: CatchObject) {
        obj.judged = true
        obj.entity?.detachSelf()
        obj.entity = null
    }

    private fun showJudgement(text: String, color: Color4) {
        judgementText.text = text
        judgementText.color = color
        judgementText.alpha = 1f
        judgementTimeRemaining = JUDGEMENT_DURATION
    }

    /**
     * osu!catch accuracy counts caught objects against catchable objects. Bananas are excluded
     * because they are pure bonus.
     */
    private fun calculateAccuracy(): Double {
        val catchable = caughtFruits + caughtDroplets + missCount
        return if (catchable == 0) 1.0 else (caughtFruits + caughtDroplets).toDouble() / catchable
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
        moveDirection = 0
        isDashing = false

        if (songHasStarted) {
            songService.pause()
        }
        pauseDialog.show()
    }

    fun togglePause() {
        if (!isReady || isFinished) {
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

        pauseDialog.hide()
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

        // Caught fruits map onto 300s and droplets onto 100s so the existing results screen can
        // present catch stats without a bespoke layout.
        val stat = StatisticV2().apply {
            setMod(mods)
            setPlayerName(Config.getOnlineUsername())
            setTime(System.currentTimeMillis())
            setHit300(caughtFruits)
            setHit100(caughtDroplets)
            setMisses(missCount)
            setScoreMaxCombo(maxCombo)
            setTotalScore(score)
            setBeatmapNoteCount(caughtFruits + caughtDroplets + missCount)
            setBeatmapMaxCombo(caughtFruits + caughtDroplets + missCount)
            setDiffModifier(1f)
            calculateModScoreMultiplier(null)
        }

        val adapter = gameAdapter ?: CatchGameSceneAdapter().also { gameAdapter = it }
        val scoring = scoringScene ?: ScoringScene(
            global.engine,
            adapter,
            global.songMenu
        ).also { scoringScene = it }

        // mapMD5 is null so the MD5 equality check fails, which is what keeps beta scores out of
        // the score database.
        scoring.load(stat, beatmapInfo, songService, null, null, null)
        global.engine.scene = scoring.scene
    }

    private fun restart() {
        pauseDialog.hide()
        loadFailedDialog.hide()
        cleanup()
        start(beatmapInfo, mods)
    }

    fun cancelLoading() {
        loadingJob?.cancel()
        loadingJob = null
    }

    fun exitToSongMenu() {
        pauseDialog.hide()
        loadFailedDialog.hide()
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
     * A thin adapter letting the existing GameLoaderScene and ScoringScene work with
     * CatchGameScene without requiring it to extend GameScene, mirroring the taiko adapter.
     */
    inner class CatchGameSceneAdapter : GameScene(global.engine) {
        init {
            // GameLoaderScene accesses gameScene.hud for the transition animation. A fresh empty
            // GameplayHUD is safe -- it is never attached or updated in catch mode.
            hud = GameplayHUD()
            isReadyToStart = false
        }

        override fun start() {
            global.engine.scene = this@CatchGameScene
            beginGameplay()
        }

        override fun cancelLoading(): CompletableFuture<Unit> {
            this@CatchGameScene.cancelLoading()
            return CompletableFuture.completedFuture(Unit)
        }

        override fun startGame(beatmapInfo: BeatmapInfo?, replayFile: String?, mods: ModHashMap?) {
            // Called by ScoringScene's retry button.
            restart()
        }

        override fun loadStoryboard(beatmapInfo: BeatmapInfo?) {
            // No-op: catch beta does not support storyboards.
        }

        override fun loadVideo(beatmapInfo: BeatmapInfo?) {
            // No-op: catch beta does not support video backgrounds.
        }
    }

    companion object {
        /** The beatmap mode id osu! uses for native osu!catch maps. */
        private const val CATCH_BEATMAP_MODE = 2

        /** The osu!catch playfield is 512 osu!pixels wide, like osu!standard. */
        private const val CATCH_PLAYFIELD_WIDTH = 512f

        /** Fruit and catcher sizes at circle size 5, in osu!pixels. */
        private const val FRUIT_OSU_DIAMETER = 56f
        private const val CATCHER_OSU_WIDTH = 106.75f

        /** Droplets are noticeably smaller than fruits. */
        private const val DROPLET_SIZE_RATIO = 0.45f

        /**
         * Only the middle of the catcher actually catches, which is what makes edge catches in
         * osu!catch a real risk rather than a formality.
         */
        private const val ALLOWED_CATCH_RANGE = 0.8f

        /** Catcher movement speed in osu!pixels per millisecond, walking and dashing. */
        private const val BASE_SPEED_OSU_PX_PER_MS = 0.5f
        private const val DASH_SPEED_OSU_PX_PER_MS = 1.0f

        /** Two taps on the same side within this window start a dash. */
        private const val DOUBLE_TAP_WINDOW_MS = 250L

        /** Bananas are spread roughly this far apart across a banana shower. */
        private const val BANANA_SPACING_MS = 250.0

        private const val SONG_INTRO_DURATION_MS = 2000L
        private const val SKIP_TOUCH_RADIUS = 250f
        private const val JUDGEMENT_DURATION = 0.35f

        private const val CATCH_DECAY_DURATION = 0.18f
        private const val CATCH_DECAY_VELOCITY_Y = -520f
        private const val MISS_DECAY_DURATION = 0.35f
        private const val MISS_DECAY_VELOCITY_Y = 620f

        private const val FRUIT_SCORE = 300L
        private const val DROPLET_SCORE = 100L
        private const val BANANA_SCORE = 1100L
        private const val COMBO_BONUS = 12L

        private val FRUIT_COLOR = Color4(0xFFEC407A)
        private val DROPLET_COLOR = Color4(0xFF4FC3F7)
        private val BANANA_COLOR = Color4(0xFFFFEE58)

        @JvmStatic
        @JvmOverloads
        fun start(beatmapInfo: BeatmapInfo, mods: ModHashMap = ModHashMap()) {
            if (beatmapInfo.beatmapMode != CATCH_BEATMAP_MODE) {
                ToastLogger.showText("This is not a native osu!catch beatmap.", true)
                return
            }

            CatchGameScene(beatmapInfo, mods).also {
                it.beginLoadingWithLoader()
            }
        }
    }
}
