package com.osudroid.ui.v2.taiko

import android.util.Log
import com.osudroid.GameMode
import com.osudroid.beatmaps.TaikoHitWindow
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
import com.osudroid.scoring.ScoreMultiplierCalculator
import com.osudroid.ui.v2.hud.HUDElement
import com.osudroid.ui.v2.hud.HUDElementSkinData
import com.osudroid.ui.v2.hud.HUDSkinData
import com.osudroid.ui.v2.hud.elements.HUDAccuracyCounter
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
import com.reco1l.andengine.sprite.UISprite
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
import org.anddev.andengine.opengl.texture.region.TextureRegion
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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * A deliberately isolated native osu!taiko beta scene.
 *
 * Beta scores never enter the osu!droid score/replay database. This keeps existing leaderboard,
 * replay, multiplayer, and performance calculations trustworthy while the ruleset is still evolving.
 *
 * The playfield object model lives in `TaikoObjects.kt`, skin resolution in `TaikoSkin.kt` and the
 * tuning constants in `TaikoGameSceneConstants.kt`.
 *
 *Behaviour follows the osu!stable client where it can be verified:
 *
 * - Hit windows come from [TaikoHitWindow], which bends at OD 5 the way stable does.
 * - Scoring is stable's ScoreV1 for osu!taiko.
 * - Health never drains, starts empty and must reach 50% to pass.
 * - Colour matters: a wrong-colour tap on a note is a miss, and a big note only pays double when
 *   both colours land together.
 */
class TaikoGameScene private constructor(
    private val beatmapInfo: BeatmapInfo,
    private val mods: ModHashMap
) : UIScene() {
    private val resources = ResourceManager.getInstance()
    private val global = GlobalManager.getInstance()
    private val songService = global.songService
    private val loadingScope = CoroutineScope(Dispatchers.Default)
    private var loadingJob: Job? = null

    private val screenWidth = Config.getRES_WIDTH().toFloat()
    private val screenHeight = Config.getRES_HEIGHT().toFloat()

    /**
     * Stable authors its taiko art against a 768px-tall playfield and scales the whole field with
     * the window. The bar starts at y=216, is 200px tall, the input drum is 180px wide and the
     * judgement circle sits at x=256. Those are the proportions reproduced below.
     */
    private val playfieldScale = screenHeight / STABLE_REFERENCE_HEIGHT
    private val laneTop = STABLE_PLAYFIELD_Y * playfieldScale
    private val laneHeight = STABLE_PLAYFIELD_HEIGHT * playfieldScale
    private val laneBottom = laneTop + laneHeight
    private val laneY = laneTop + laneHeight / 2f

    /** `taiko-bar-left` occupies the whole left edge of the bar and centres the drum face. */
    private val drumWidth = STABLE_INPUT_DRUM_WIDTH * playfieldScale
    private val drumCenterX = drumWidth / 2f
    private val drumFaceDiameter = laneHeight * 0.62f

    /** Where notes have to be when they are hit. */
    private val targetX = STABLE_HIT_TARGET_X * playfieldScale

    private val normalNoteDiameter = laneHeight * STABLE_NORMAL_NOTE_SCALE
    private val bigNoteDiameter = normalNoteDiameter * STABLE_STRONG_SCALE
    private val spawnX = screenWidth + bigNoteDiameter

    /** Diameter of the approach circle / burst drawn on the hit position. */
    private val targetDiameter = laneHeight * STABLE_APPROACH_SCALE
    private val burstDiameter = laneHeight * 1.05f

    /** Stable's `spinner-circle` and the box that holds its shrinking approach ring. */
    private val spinnerDiameter = laneHeight * 1.05f
    private val swellBoxDiameter = laneHeight * 1.7f

    private val judgementBaseY = laneY - burstDiameter * 0.85f

    /** Distance a note travels from spawn to the judgement circle. */
    private val travelDistance = spawnX - targetX

    private val playfield = UIContainer()
    private val hudLayer = UIContainer()
    private lateinit var scoreCounter: HUDScoreCounter
    private lateinit var accuracyCounter: HUDAccuracyCounter
    private lateinit var healthBar: HUDHealthBar
    private lateinit var songProgress: HUDSongProgress
    private lateinit var skipButton: UIAnimatedSprite

    /** Stable's `taiko-slider` tiles, scrolling left above the bar. */
    private lateinit var scrollerLayer: UIContainer
    private val scrollerTiles = mutableListOf<Triple<UIContainer, UIComponent, UIComponent>>()
    private var scrollerTileWidth = 0f
    private var scrollerOffset = 0f

    /** `taiko-bar-right-glow`, crossfaded in during kiai time. */
    private var barGlow: UISprite? = null

    /** Stable flashes the corresponding half of the drum on every tap. */
    private lateinit var donFlash: UIContainer
    private lateinit var katFlash: UIContainer

    /** `taiko-glow` / drawn sunburst behind the judgement circle. */
    private lateinit var kiaiGlow: UIContainer

    /** Combo milestone flower and the four pippidon states. */
    private lateinit var milestoneRing: UICircle
    private var flowerBurst: UISprite? = null
    private val mascotSprites = mutableMapOf<MascotState, UIAnimatedSprite>()
    private var mascotState = MascotState.Idle
    private var mascotStateTimeRemaining = 0f

    private lateinit var comboText: UIText
    private lateinit var songIntro: UIContainer
    private val judgementText: UIText
    private lateinit var loadingText: UIText

    /**
     * The pause menu, built on the same shared dialog component the rest of the game uses rather
     * than a bespoke container, so it matches the standard pause presentation and picks up the
     * app's theme, backdrop and show/hide animations for free.
     */
    private val pauseDialog = UIMessageDialog().apply {
        // A stray tap outside the card must not silently resume a paused run.
        staticBackdrop = true
        title = "osu!taiko (BETA)"
        text = "Paused\n\nBeta scores are kept separate and are not uploaded."

        addButton {
            text = "Resume"
            isSelected = true
            onActionUp = { this@TaikoGameScene.resume() }
        }

        addButton {
            text = "Restart"
            onActionUp = { this@TaikoGameScene.restart() }
        }

        addButton {
            text = "Exit to songs"
            onActionUp = { this@TaikoGameScene.exitToSongMenu() }
        }
    }

    /** Shown when the beatmap cannot be parsed or its audio cannot be loaded. */
    private val loadFailedDialog = UIMessageDialog().apply {
        staticBackdrop = true
        title = "osu!taiko (BETA)"
        text = "Could not load this osu!taiko map."

        addButton {
            text = "Try again"
            isSelected = true
            onActionUp = { this@TaikoGameScene.restart() }
        }

        addButton {
            text = "Exit to songs"
            onActionUp = { this@TaikoGameScene.exitToSongMenu() }
        }
    }

    private var objects = emptyList<TaikoObject>()
    /** Notes before this index are all judged, so scanning can start here. */
    private var firstActiveIndex = 0
    private val decayingEntities = mutableListOf<DecayingEntity>()

    /** Control points are kept around so kiai time can be resolved at any point in the map. */
    private var controlPoints: BeatmapControlPoints? = null

    // --- Hit windows ---------------------------------------------------------------------------

    // Written with an explicit Double argument: `TaikoHitWindow()` is ambiguous because the class
    // declares both a `Double?` primary constructor and a `Float?` secondary one, each defaulted.
    private val hitWindow = TaikoHitWindow(5.0)
    private val greatWindow get() = hitWindow.greatWindow
    private val okWindow get() = hitWindow.okWindow

    /** `TaikoHitWindow` calls this `mehWindow`; osu!taiko has no meh, it is the miss window. */
    private val missWindow get() = hitWindow.mehWindow

    // --- Scoring -------------------------------------------------------------------------------

    /**
     * Stable's "n": how much each ten combo is worth. See [TAIKO_SCORE_MULTIPLIERS].
     */
    private var taikoScoreMultiplier = DEFAULT_TAIKO_SCORE_MULTIPLIER

    /** Score multiplier of the selected mods, folded into stable's combo step. */
    private var modScoreMultiplier = 1f

    /**
     * Health gained or lost by a single object, as a fraction of the bar.
     *
     * Scaled so that a run which hits every note fills the bar exactly once. Only don/kat notes
     * move health in osu!taiko, so that is the count this is derived from.
     */
    private var healthUnit = 0f

    private var score = 0L
    private var combo = 0
    private var maxCombo = 0
    private var greatCount = 0
    private var goodCount = 0
    private var missCount = 0
    private var health = 0f
    private val isAutoPlay = mods.contains(ModAutoplay::class.java)

    // --- Input ---------------------------------------------------------------------------------

    /** Time of the most recent tap, used to tell two taps apart from one simultaneous pair. */
    private var lastInputTime = Double.NEGATIVE_INFINITY
    private var lastInputIsKat: Boolean? = null

    /**
     * The note judged by the most recent tap.
     *
     * A big note hit with a single key stays reachable for [STRONG_INPUT_WINDOW] so that the
     * opposite colour landing immediately afterwards can upgrade it to a strong hit.
     */
    private var lastJudgedNote: TaikoObject? = null

    // --- Presentation --------------------------------------------------------------------------

    private var judgementTimeRemaining = 0f
    private var donFlashTimeRemaining = 0f
    private var katFlashTimeRemaining = 0f
    private var milestoneTimeRemaining = 0f
    private var kiaiGlowAlpha = 0f
    private var kiaiPulseTimeRemaining = 0f
    private var barGlowAlpha = 0f
    private var scrollerPassing = 1f
    private var isPassing = true
    private var kiaiFlashAlpha = 0f

    // --- Lifecycle -----------------------------------------------------------------------------

    private var isReady = false
    private var isPaused = false
    private var isFinished = false
    private var introShownAt = System.currentTimeMillis()
    private var isBeatmapLoaded = false
    private var songHasStarted = false
    private var leadInRemaining = 0.0
    private var firstObjectStartTime = 0.0
    private var lastObjectEndTime = 0.0
    private var skipTargetTime = 0.0

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

        buildScroller()
        buildLane()
        buildDrum()
        buildHitTarget()

        attachChild(playfield)

        judgementText = text {
            x = targetX - burstDiameter
            y = judgementBaseY
            width = burstDiameter * 2f
            alignment = Anchor.TopCenter
            font = resources.getFont("middleFont")
            text = ""
        }

        createTaikoHud()
        buildSkipButton()
        buildSongIntro()

        if (!isAutoPlay) {
            setOnSceneTouchListener { _, event -> handleTouch(event) }
        }
    }

    /**
     * The upper half of stable's playfield: `taiko-slider` scrolling left in a seamless loop, with
     * `taiko-slider-fail` taking over for 200ms after a miss. Both are optional; without them a
     * warm star field is drawn so the top of the screen still reads as stable's playfield.
     */
    private fun buildScroller() {
        val passingTexture = TaikoSkin[TaikoSkinNames.SLIDER]
        val failingTexture = TaikoSkin[TaikoSkinNames.SLIDER_FAIL] ?: passingTexture

        scrollerTileWidth = if (passingTexture != null && passingTexture.height > 0) {
            max(
                laneTop * 0.5f,
                passingTexture.width.toFloat() / passingTexture.height.toFloat() * laneTop
            )
        } else {
            laneTop * 1.5f
        }

        scrollerLayer = UIContainer().apply {
            x = 0f
            y = 0f
            width = screenWidth
            height = laneTop
            clipToBounds = true
        }
        attachChild(scrollerLayer)

        val tileCount = (screenWidth / scrollerTileWidth).toInt() + 3
        for (index in 0 until tileCount) {
            val tile = UIContainer().apply {
                x = index * scrollerTileWidth
                y = 0f
                width = scrollerTileWidth
                height = laneTop
            }

            val passing = createScrollerFace(passingTexture, false)
            val failing = createScrollerFace(failingTexture, true)

            tile.attachChild(passing)
            tile.attachChild(failing)
            scrollerLayer.attachChild(tile)
            scrollerTiles.add(Triple(tile, passing, failing))
        }
    }

    /** One layer of a scrolling tile. Stable crossfades the passing and failing variants. */
    private fun createScrollerFace(texture: TextureRegion?, isFail: Boolean): UIComponent {
        if (texture != null) {
            return UISprite(texture).apply {
                width = scrollerTileWidth
                height = laneTop
                scaleType = ScaleType.Stretch
                if (isFail && TaikoSkin[TaikoSkinNames.SLIDER_FAIL] == null) {
                    color = Color4(0xFF8A4A55)
                }
                alpha = if (isFail) 0f else 1f
            }
        }

        val base = if (isFail) TAIKO_SCROLLER_FAIL_COLOR else TAIKO_SCROLLER_COLOR
        val wave = if (isFail) Color4(0xFF6B3C46) else TAIKO_SCROLLER_WAVE_COLOR

        return UIContainer().apply {
            width = scrollerTileWidth
            height = laneTop
            alpha = if (isFail) 0f else 1f

            box {
                x = 0f
                y = 0f
                width = scrollerTileWidth
                height = laneTop
                color = base
            }

            // A soft wave band, the closest drawn approximation of stable's default scroller art.
            box {
                x = -scrollerTileWidth * 0.15f
                y = laneTop * 0.22f
                width = scrollerTileWidth * 1.3f
                height = laneTop * 0.55f
                cornerRadius = laneTop * 0.26f
                color = wave
                alpha = 0.65f
            }

            for (index in 0 until 12) {
                val size = laneTop * (0.045f + (index % 3) * 0.022f)
                val px = (index * 0.137f + (index % 3) * 0.07f) % 1f * (scrollerTileWidth - size)
                val py = (index * 0.211f + (index % 4) * 0.05f) % 1f * (laneTop - size)

                circle {
                    x = px
                    y = py
                    width = size
                    height = size
                    color = Color4.White
                    alpha = 0.8f
                }
            }
        }
    }

    /**
     * The lower half of stable's playfield: `taiko-bar-right` with `taiko-bar-right-glow` overlaid
     * during kiai. Notes are drawn on top of this strip; the beatmap background stays visible below.
     */
    private fun buildLane() {
        val barTexture = TaikoSkin[TaikoSkinNames.BAR_RIGHT]
        val glowTexture = TaikoSkin[TaikoSkinNames.BAR_RIGHT_GLOW]

        if (barTexture != null) {
            sprite {
                x = 0f
                y = laneTop
                width = FillParent
                height = laneHeight
                scaleType = ScaleType.Stretch
                textureRegion = barTexture
            }
        } else {
            box {
                x = 0f
                y = laneTop
                width = FillParent
                height = laneHeight
                color = TAIKO_BAR_COLOR
                alpha = 0.96f
            }

            // Stable's bar is lighter along the top and drops into shadow at the bottom.
            box {
                x = 0f
                y = laneTop
                width = FillParent
                height = laneHeight * 0.42f
                color = TAIKO_BAR_TOP_COLOR
                alpha = 0.85f
            }
        }

        barGlow = if (glowTexture != null) {
            sprite {
                x = 0f
                y = laneTop
                width = FillParent
                height = laneHeight
                scaleType = ScaleType.Stretch
                textureRegion = glowTexture
                alpha = 0f
            }
        } else {
            null
        }

        buildKiaiGlow()

        box {
            x = 0f
            y = laneTop
            width = FillParent
            height = 2.5f * playfieldScale
            color = Color4(0xFF4A4A57)
            alpha = 0.9f
        }

        box {
            x = 0f
            y = laneBottom - 2.5f * playfieldScale
            width = FillParent
            height = 2.5f * playfieldScale
            color = Color4(0xFF050507)
            alpha = 0.9f
        }
    }

    /**
     * `taiko-glow` lives behind the judgement circle, not behind the drum. In kiai it fades in over
     * 100ms, out over 600ms and expands briefly on every hit.
     */
    private fun buildKiaiGlow() {
        val texture = TaikoSkin[TaikoSkinNames.GLOW]
        val size = laneHeight * 1.25f
        val rayThickness = laneHeight * 0.035f

        kiaiGlow = UIContainer().apply {
            x = targetX - size / 2f
            y = laneY - size / 2f
            width = size
            height = size
            origin = Anchor.Center
            alpha = 0f

            if (texture != null) {
                sprite {
                    x = 0f
                    y = 0f
                    width = size
                    height = size
                    scaleType = ScaleType.Fit
                    textureRegion = texture
                    color = KIAI_GLOW_COLOR
                }
            } else {
                // Drawn sunburst, matching the star-burst glow of stable's default skin.
                for (index in 0 until 12) {
                    box {
                        x = 0f
                        y = (size - rayThickness) / 2f
                        width = size
                        height = rayThickness
                        origin = Anchor.Center
                        rotation = index * 30f
                        color = KIAI_GLOW_COLOR
                        alpha = 0.65f
                    }
                }

                val centreSize = size * 0.36f
                circle {
                    x = (size - centreSize) / 2f
                    y = (size - centreSize) / 2f
                    width = centreSize
                    height = centreSize
                    color = KIAI_GLOW_COLOR
                    alpha = 0.45f
                }
            }
        }
        attachChild(kiaiGlow)
    }

    /**
     * The drum on the far left.
     *
     * Stable draws `taiko-bar-left`, splits the drum into mirrored inner/outer halves that flash
     * when tapped, and puts the combo counter on the drum face. The playable controls stay
     * invisible and span the whole screen.
     */
    private fun buildDrum() {
        val panel = TaikoSkin[TaikoSkinNames.BAR_LEFT]
        val outer = TaikoSkin[TaikoSkinNames.DRUM_OUTER]
        val inner = TaikoSkin[TaikoSkinNames.DRUM_INNER]

        if (panel != null) {
            sprite {
                x = 0f
                y = laneTop
                width = drumWidth
                height = laneHeight
                scaleType = ScaleType.Stretch
                textureRegion = panel
            }
        } else {
            box {
                x = 0f
                y = laneTop
                width = drumWidth
                height = laneHeight
                color = TAIKO_PANEL_COLOR
            }
        }

        // Drawn fallback for the drum face: a cream centre inside a red/blue rim.
        val faceSize = drumFaceDiameter
        circle {
            x = drumCenterX - faceSize / 2f
            y = laneY - faceSize / 2f
            width = faceSize
            height = faceSize
            color = Color4(0xFFFFF3DC)
        }
        circle {
            x = drumCenterX - faceSize / 2f
            y = laneY - faceSize / 2f
            width = faceSize
            height = faceSize
            color = DON_COLOR
            alpha = 0.85f
            paintStyle = PaintStyle.Outline
            lineWidth = faceSize * 0.075f
        }
        circle {
            val centreSize = faceSize * 0.46f
            x = drumCenterX - centreSize / 2f
            y = laneY - centreSize / 2f
            width = centreSize
            height = centreSize
            color = Color4(0xFFF8E1E1)
            alpha = 0.9f
        }

        donFlash = buildDrumFlash(inner, DON_COLOR, true)
        katFlash = buildDrumFlash(outer, KAT_COLOR, false)
        attachChild(donFlash)
        attachChild(katFlash)

        comboText = text {
            x = 0f
            y = laneY - 26f
            width = drumWidth
            alignment = Anchor.TopCenter
            font = resources.getFont("strokeFont")
            text = ""
        }

        // Combo milestones every 50 hits: a flower/ring pulsing out behind the mascot.
        milestoneRing = circle {
            val size = laneHeight * 0.9f
            x = drumCenterX - size / 2f
            y = laneTop + laneHeight * 0.2f - size
            width = size
            height = size
            color = Color4(0xFFFFF176)
            alpha = 0f
            paintStyle = PaintStyle.Outline
            lineWidth = 6f
        }

        buildMascot()

    }

    /**
     * One of the two mirrored half-drum highlights stable fades in on a tap. Skins provide a half
     * drum image; the fallback is a coloured circle over the drum face.
     */
    private fun buildDrumFlash(texture: TextureRegion?, color: Color4, isCentre: Boolean): UIContainer =
        UIContainer().apply {
            x = 0f
            y = laneTop
            width = drumWidth
            height = laneHeight
            alpha = 0f

            if (texture != null) {
                val half = drumWidth / 2f

                // `flippedHorizontal` mutates the TextureRegion itself, so the mirrored half gets
                // its own copy. Otherwise the shared drum texture would be flipped for every other
                // sprite using it too.
                val mirrored = texture.deepCopy()

                sprite {
                    x = 0f
                    y = 0f
                    width = half
                    height = laneHeight
                    scaleType = ScaleType.Stretch
                    textureRegion = texture
                }

                sprite {
                    x = half
                    y = 0f
                    width = half
                    height = laneHeight
                    scaleType = ScaleType.Stretch
                    textureRegion = mirrored
                    flippedHorizontal = true
                }
            } else {
                val size = drumFaceDiameter * if (isCentre) 0.62f else 1f

                circle {
                    x = drumCenterX - size / 2f
                    y = laneY - laneTop - size / 2f
                    width = size
                    height = size
                    this.color = color
                    alpha = 0.55f
                }
            }
        }

    /** Pippidon idle / kiai / fail / clear animations, positioned above the drum. */
    private fun buildMascot() {
        val height = laneHeight * 0.95f
        val width = drumWidth * 1.2f
        val bottom = laneTop + laneHeight * 0.2f

        for (state in MascotState.values()) {
            val frames = TaikoSkin.pippidonFrames(state)
            if (frames.isEmpty()) {
                continue
            }

            val sprite = UIAnimatedSprite(frames.map { it as TextureRegion? }.toTypedArray()).apply {
                x = 0f
                y = bottom - height
                this.width = width
                this.height = height
                scaleType = ScaleType.Fit
                frameTime = 0.12f
                isVisible = state == MascotState.Idle
            }

            mascotSprites[state] = sprite
            attachChild(sprite)
        }

        val flower = TaikoSkin.flower()
        if (flower != null) {
            flowerBurst = sprite {
                x = -drumWidth * 0.1f
                y = bottom - height * 0.7f
                this.width = drumWidth * 1.4f
                this.height = height * 0.7f
                scaleType = ScaleType.Fit
                textureRegion = flower
                origin = Anchor.Center
                isVisible = false
                alpha = 0f
            }
        }
    }

    /**
     * The judgement circle the notes are struck on.
     *
     * Stable overlays `approachcircle` (alpha 0.47, scale 0.83) and `taikobigcircle` (alpha 0.22,
     * scale 0.8). Without those skins it draws a thin double ring and the two vertical bar guides.
     */
    private fun buildHitTarget() {
        val approachCircle = TaikoSkin[TaikoSkinNames.APPROACH_CIRCLE]
        val bigCircle = TaikoSkin[TaikoSkinNames.BIG_CIRCLE]

        if (approachCircle != null && bigCircle != null) {
            val approachSize = laneHeight * STABLE_APPROACH_SCALE
            sprite {
                x = targetX - approachSize / 2f
                y = laneY - approachSize / 2f
                width = approachSize
                height = approachSize
                scaleType = ScaleType.Fit
                textureRegion = approachCircle
                alpha = STABLE_APPROACH_ALPHA
            }

            val bigSize = laneHeight * STABLE_BIG_TARGET_SCALE
            sprite {
                x = targetX - bigSize / 2f
                y = laneY - bigSize / 2f
                width = bigSize
                height = bigSize
                scaleType = ScaleType.Fit
                textureRegion = bigCircle
                alpha = STABLE_BIG_TARGET_ALPHA
            }
        } else {
            val lineWidth = 2.5f * playfieldScale

            circle {
                x = targetX - bigNoteDiameter / 2f
                y = laneY - bigNoteDiameter / 2f
                width = bigNoteDiameter
                height = bigNoteDiameter
                color = Color4.White
                alpha = 0.1f
                paintStyle = PaintStyle.Outline
                this.lineWidth = lineWidth
            }

            circle {
                x = targetX - normalNoteDiameter / 2f
                y = laneY - normalNoteDiameter / 2f
                width = normalNoteDiameter
                height = normalNoteDiameter
                color = Color4.White
                alpha = 0.5f
                paintStyle = PaintStyle.Outline
                this.lineWidth = lineWidth
            }

            val guideHeight = (laneHeight - bigNoteDiameter) / 2f
            val guideWidth = lineWidth

            box {
                x = targetX - guideWidth / 2f
                y = laneTop
                width = guideWidth
                height = guideHeight
                color = Color4.White
                alpha = 0.1f
            }

            box {
                x = targetX - guideWidth / 2f
                y = laneBottom - guideHeight
                width = guideWidth
                height = guideHeight
                color = Color4.White
                alpha = 0.1f
            }
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
        healthBar = HUDHealthBar()

        attach(scoreCounter, dataFor(HUDScoreCounter::class))
        attach(accuracyCounter, dataFor(HUDAccuracyCounter::class))
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

    private fun buildSkipButton() {
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
    }

    private fun buildSongIntro() {
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

                val od = parsed.difficulty.od.toDouble().coerceIn(0.0, 10.0)

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

                // Only don/kat notes move health, so the bar is scaled by that count rather than by
                // every object on the field.
                val noteCount = taikoObjects.count { it.isNote }

                val calculatedMultiplier = calculateTaikoScoreMultiplier(
                    objectCount = noteCount,
                    drainTimeSeconds = (lastObjectTime - firstObjectTime) / 1000.0,
                    od = od,
                    hp = parsed.difficulty.hp.toDouble()
                )
                val calculatedModMultiplier =
                    ScoreMultiplierCalculator(parsed.difficulty).calculateFor(mods.values).toFloat()

                updateThread {
                    objects = taikoObjects
                    firstActiveIndex = 0
                    controlPoints = parsed.controlPoints

                    // osu!taiko hit windows bend at OD 5, which TaikoHitWindow already encodes.
                    hitWindow.overallDifficulty = od

                    taikoScoreMultiplier = calculatedMultiplier
                    modScoreMultiplier = calculatedModMultiplier
                    healthUnit = if (noteCount > 0) 1f / noteCount else 0f

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
                    val reason = "Could not load this osu!taiko map.\n${e.message.orEmpty()}"
                    loadingText.text = reason
                    loadFailedDialog.text = reason
                    loadFailedDialog.show()
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

        // Stable moves notes 0.1px/ms in its 480px gamefield at base velocity. Taiko then applies
        // an extra 1.4 multiplier on top of the beatmap's slider multiplier and the inherited
        // slider velocity, so notes read at the same pace as they do on the desktop client.
        val baseVelocity = STABLE_BASE_SCROLL_PX_PER_MS *
            (screenHeight / STABLE_GAMEFIELD_HEIGHT).toDouble()

        val effectiveVelocity = baseVelocity *
            difficulty.sliderMultiplier *
            STABLE_TAIKO_VELOCITY_MULTIPLIER *
            difficultyPoint.speedMultiplier

        val velocity = effectiveVelocity.coerceAtLeast(0.01)

        val obj = TaikoObject(
            kind,
            startTime,
            endTime,
            isBig,
            samples,
            velocity = velocity,
            preempt = (travelDistance / velocity).coerceIn(MIN_PREEMPT, MAX_PREEMPT)
        )

        when (kind) {
            ObjectKind.Denden -> {
                // osu!taiko derives the required hit count from the denden's length and the map's
                // overall difficulty, then multiplies by a legacy factor because swells are easier
                // in taiko than spinners are in osu!.
                val od = difficulty.od.toDouble().coerceIn(0.0, 10.0)
                val hitsPerSecond = difficultyRange(od, 3.0, 5.0, 7.5) * SWELL_HIT_MULTIPLIER

                obj.requiredHits = max(
                    1.0,
                    (endTime - startTime) / 1000.0 * hitsPerSecond
                ).toInt()
            }

            ObjectKind.Drumroll -> {
                // Drum rolls are scored on discrete ticks rather than on raw tapping, and stable
                // hard-caps how many of them a roll carries: four per beat, or eight per beat on
                // songs at 125 BPM or below. The window a tick can be collected within is half the
                // spacing between ticks.
                val bpm = 60000.0 / timingPoint.msPerBeat
                val ticksPerBeat =
                    if (bpm <= ROLL_SLOW_BPM_THRESHOLD) ROLL_TICKS_PER_BEAT_SLOW else ROLL_TICKS_PER_BEAT
                val tickSpacing = timingPoint.msPerBeat / ticksPerBeat

                if (tickSpacing > 0.0) {
                    val ticks = mutableListOf<Double>()
                    var t = startTime

                    while (t < endTime + tickSpacing / 2.0) {
                        ticks.add(t)
                        t += tickSpacing
                    }

                    obj.tickTimes = ticks
                    obj.tickWindow = tickSpacing / 2.0
                }
            }

            else -> Unit
        }

        return obj
    }

    /**
     * The standard osu! difficulty scaling curve: [min] at difficulty 0, [mid] at 5 and [max] at
     * 10, interpolated linearly between those points.
     */
    private fun difficultyRange(difficulty: Double, min: Double, mid: Double, max: Double): Double =
        when {
            difficulty > 5.0 -> mid + (max - mid) * (difficulty - 5.0) / 5.0
            difficulty < 5.0 -> mid - (mid - min) * (5.0 - difficulty) / 5.0
            else -> mid
        }

    /**
     * Picks stable's "n" for [TAIKO_SCORE_MULTIPLIERS].
     *
     * Stable derives this from the beatmap's old five-star rating, which this client no longer
     * computes, so it is approximated from the inputs that rating was built from: object density
     * plus the drain and overall difficulty settings. The thresholds are placed so that an ordinary
     * map lands on 80, the value stable is documented to use for mid-high difficulties.
     */
    private fun calculateTaikoScoreMultiplier(
        objectCount: Int,
        drainTimeSeconds: Double,
        od: Double,
        hp: Double
    ): Int {
        val density = if (drainTimeSeconds > 0.0) {
            (objectCount / drainTimeSeconds * 8.0).coerceIn(0.0, 16.0)
        } else {
            0.0
        }

        val rating = (hp + od + density) / 38.0 * 10.0

        for (index in TAIKO_SCORE_MULTIPLIER_THRESHOLDS.indices) {
            if (rating < TAIKO_SCORE_MULTIPLIER_THRESHOLDS[index]) {
                return TAIKO_SCORE_MULTIPLIERS[index]
            }
        }
        return TAIKO_SCORE_MULTIPLIERS.last()
    }

    /** Whether kiai (Go-Go) time is in effect at [time]. */
    private fun isKiaiAt(time: Double): Boolean =
        controlPoints?.effect?.controlPointAt(time)?.isKiai ?: false

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
            updatePlayfieldVisuals(deltaTimeSec, now)
            updateNotePresentation(now)
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

        updateHitFeedback(deltaTimeSec)

        super.onManagedUpdate(deltaTimeSec)
    }

    private fun updateHitFeedback(deltaTimeSec: Float) {
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

        // Stable's drum halves: fade in 80ms, hold 100ms, fade out 50ms.
        donFlashTimeRemaining = (donFlashTimeRemaining - deltaTimeSec).coerceAtLeast(0f)
        katFlashTimeRemaining = (katFlashTimeRemaining - deltaTimeSec).coerceAtLeast(0f)
        donFlash.alpha = drumFlashAlpha(donFlashTimeRemaining)
        katFlash.alpha = drumFlashAlpha(katFlashTimeRemaining)

        // Combo milestone flower / ring, pulsing out behind the mascot.
        if (milestoneTimeRemaining > 0f) {
            milestoneTimeRemaining -= deltaTimeSec

            val progress =
                1f - (milestoneTimeRemaining / COMBO_MILESTONE_DURATION).coerceIn(0f, 1f)
            val diameter = laneHeight * (0.6f + 0.8f * progress)
            val centreY = laneTop + laneHeight * 0.2f - laneHeight * 0.45f

            milestoneRing.width = diameter
            milestoneRing.height = diameter
            milestoneRing.x = drumCenterX - diameter / 2f
            milestoneRing.y = centreY - diameter / 2f
            milestoneRing.alpha = (0.9f * (1f - progress)).coerceIn(0f, 1f)

            flowerBurst?.let { flower ->
                flower.isVisible = true
                flower.setScale(0.6f + 0.8f * progress)
                flower.alpha = (0.85f * (1f - progress)).coerceIn(0f, 1f)
            }
        } else {
            milestoneRing.alpha = 0f
            flowerBurst?.isVisible = false
        }

        comboText.text = if (combo > 0) combo.toString() else ""
    }

    /** Alpha of one of stable's half-drum flashes at the current point in its 230ms lifetime. */
    private fun drumFlashAlpha(remaining: Float): Float {
        if (remaining <= 0f) {
            return 0f
        }

        val elapsed = DRUM_FLASH_DURATION - remaining

        return when {
            elapsed < DRUM_FLASH_IN -> elapsed / DRUM_FLASH_IN
            elapsed < DRUM_FLASH_IN + DRUM_FLASH_HOLD -> 1f
            else -> (1f - (elapsed - DRUM_FLASH_IN - DRUM_FLASH_HOLD) / DRUM_FLASH_OUT)
                .coerceIn(0f, 1f)
        }
    }

    /**
     * Drives everything stable animates on the playfield itself: the scroller, the kiai bar, the
     * glow behind the judgement circle, the mascot and the beat-synced note flash.
     */
    private fun updatePlayfieldVisuals(deltaTimeSec: Float, now: Double) {
        // `taiko-slider` scrolls left at 0.1px/ms in stable's 768px reference space.
        scrollerOffset -= STABLE_SCROLLER_PX_PER_MS * playfieldScale * deltaTimeSec * 1000f
        if (scrollerTileWidth > 0f) {
            scrollerOffset %= scrollerTileWidth
            if (scrollerOffset > 0f) {
                scrollerOffset -= scrollerTileWidth
            }

            val passingTarget = if (isPassing) 1f else 0f
            val passingStep = deltaTimeSec / SCROLLER_FAIL_FADE
            scrollerPassing = if (scrollerPassing < passingTarget) {
                min(passingTarget, scrollerPassing + passingStep)
            } else {
                max(passingTarget, scrollerPassing - passingStep)
            }

            scrollerTiles.forEachIndexed { index, triple ->
                triple.first.x = index * scrollerTileWidth + scrollerOffset
                triple.second.alpha = scrollerPassing
                triple.third.alpha = 1f - scrollerPassing
            }
        }

        val isKiai = isReady && !isFinished && isKiaiAt(now)

        // `taiko-bar-right-glow` crossfades over 200ms.
        val barTarget = if (isKiai) 1f else 0f
        val barStep = deltaTimeSec / BAR_GLOW_FADE
        barGlowAlpha = if (barGlowAlpha < barTarget) {
            min(barTarget, barGlowAlpha + barStep)
        } else {
            max(barTarget, barGlowAlpha - barStep)
        }
        barGlow?.alpha = barGlowAlpha

        // `taiko-glow`: 100ms in, 600ms out, with an 80ms pulse on every hit.
        val glowTarget = if (isKiai) 1f else 0f
        val glowStep = deltaTimeSec / if (isKiai) KIAI_GLOW_FADE_IN else KIAI_GLOW_FADE_OUT
        kiaiGlowAlpha = if (kiaiGlowAlpha < glowTarget) {
            min(glowTarget, kiaiGlowAlpha + glowStep)
        } else {
            max(glowTarget, kiaiGlowAlpha - glowStep)
        }

        var glowScale = 1f
        if (kiaiPulseTimeRemaining > 0f) {
            kiaiPulseTimeRemaining = (kiaiPulseTimeRemaining - deltaTimeSec).coerceAtLeast(0f)
            val pulse = (kiaiPulseTimeRemaining / KIAI_GLOW_PULSE).coerceIn(0f, 1f)
            glowScale = 1f + (KIAI_GLOW_PULSE_SCALE - 1f) * pulse
        }
        kiaiGlow.alpha = kiaiGlowAlpha
        kiaiGlow.setScale(glowScale)

        // Pippidon returns to idle/kiai once its fail or clear animation has played once.
        if (mascotStateTimeRemaining > 0f) {
            mascotStateTimeRemaining -= deltaTimeSec
            if (mascotStateTimeRemaining <= 0f) {
                mascotState = if (isKiai) MascotState.Kiai else MascotState.Idle
            }
        } else if (mascotState == MascotState.Clear || mascotState == MascotState.Fail) {
            mascotState = if (isKiai) MascotState.Kiai else MascotState.Idle
        } else {
            mascotState = if (isKiai) MascotState.Kiai else MascotState.Idle
        }
        updateMascot(now)

        // Kiai note flash: 0.3 alpha on the beat, decaying over max(80ms, beat length - 80ms).
        val timing = controlPoints?.timing?.controlPointAt(now)
        val beatLength = (timing?.msPerBeat ?: 500.0).coerceAtLeast(1.0)
        val phase = if (timing != null) {
            ((now - timing.time) % beatLength + beatLength) % beatLength
        } else {
            0.0
        }
        val flashDuration = max(KIAI_FLASH_MIN_DURATION, beatLength - KIAI_FLASH_MIN_DURATION)
        kiaiFlashAlpha = if (isKiai && phase <= flashDuration) {
            KIAI_FLASH_ALPHA * (1f - (phase / flashDuration).toFloat())
        } else {
            0f
        }
    }

    /** Shows one pippidon animation and keeps its frame rate tied to the current BPM. */
    private fun updateMascot(now: Double) {
        val beatLength = (controlPoints?.timing?.controlPointAt(now)?.msPerBeat ?: 500.0)
            .coerceAtLeast(1.0)
        val frameTime = (beatLength / 1000.0 / 4.0).toFloat().coerceIn(0.04f, 0.2f)

        for ((state, sprite) in mascotSprites) {
            if (state == mascotState) {
                sprite.isVisible = true
                sprite.frameTime = frameTime
                if (!sprite.isPlaying) {
                    sprite.play()
                }
            } else {
                sprite.isVisible = false
                sprite.stop()
            }
        }
    }

    /** Plays one of stable's one-shot mascot states for [duration] seconds. */
    private fun setMascotState(state: MascotState, duration: Float) {
        val sprite = mascotSprites[state] ?: return
        mascotState = state
        mascotStateTimeRemaining = duration

        // Restart the one-shot animation without Entity.reset(): that would also zero the sprite's
        // position, scale and visibility, which are owned by the playfield layout.
        sprite.stop()
        sprite.play()
    }

    /** Flashes the drum half that was tapped, the way stable does. */
    private fun flashDrum(isKat: Boolean) {
        if (isKat) {
            katFlashTimeRemaining = DRUM_FLASH_DURATION
        } else {
            donFlashTimeRemaining = DRUM_FLASH_DURATION
        }
    }

    /** Pulses `taiko-glow` when a note is hit during kiai time. */
    private fun pulseKiaiGlow(now: Double) {
        if (isKiaiAt(now)) {
            kiaiPulseTimeRemaining = KIAI_GLOW_PULSE
        }
    }

    /**
     * Stable alternates the two note overlay frames on the beat from 50 combo, and doubles the
     * rate at 150. Notes also flash on the beat during kiai time.
     */
    private fun updateNotePresentation(now: Double) {
        val timing = controlPoints?.timing?.controlPointAt(now)
        val beatLength = (timing?.msPerBeat ?: 500.0).coerceAtLeast(1.0)
        val beatTime = timing?.time ?: 0.0

        val multiplier = when {
            combo >= COMBO_MILESTONE_FAST_INTERVAL -> 2
            combo >= COMBO_MILESTONE_INTERVAL -> 1
            else -> 0
        }

        val overlayFrame = if (multiplier == 0) {
            0
        } else {
            val period = beatLength * 2.0 / multiplier
            val phase = (abs(now - beatTime) % period + period) % period
            if (phase >= beatLength / multiplier) 0 else 1
        }

        for (index in firstActiveIndex until objects.size) {
            val obj = objects[index]

            if (obj.startTime - obj.preempt > now) {
                break
            }
            if (obj.judged) {
                continue
            }

            if (obj.noteOverlayFrames.size >= 2) {
                obj.noteOverlayFrames[0].isVisible = overlayFrame == 0
                obj.noteOverlayFrames[1].isVisible = overlayFrame == 1
            }
            obj.noteFlashSprite?.alpha = kiaiFlashAlpha
        }
    }

    /** The current audio time, in milliseconds. */
    private val currentTime: Double
        get() = if (songHasStarted) songService.positionPrecise else -leadInRemaining

    private fun easeOutQuad(value: Float): Float = 1f - (1f - value) * (1f - value)
    private fun easeInQuad(value: Float): Float = value * value

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

            val entity = decaying.entity
            val elapsed = decaying.duration - decaying.remaining
            val progress = (elapsed / decaying.duration).coerceIn(0f, 1f)

            when (decaying.motion) {
                DecayMotion.Linear -> {
                    entity.x += decaying.velocityX * deltaTimeSec
                    entity.y += decaying.velocityY * deltaTimeSec
                    entity.alpha = (1f - progress).coerceIn(0f, 1f)
                }

                DecayMotion.Miss -> {
                    entity.x += decaying.velocityX * deltaTimeSec
                    entity.alpha = (1f - progress).coerceIn(0f, 1f)
                }

                DecayMotion.HitFly -> {
                    val fly = elapsed.coerceAtMost(HIT_FLY_DURATION)
                    val yOffset = if (fly <= HIT_FLY_UP_DURATION) {
                        -decaying.travelHeight * easeOutQuad(fly / HIT_FLY_UP_DURATION)
                    } else {
                        val down = ((fly - HIT_FLY_UP_DURATION) / HIT_FLY_DOWN_DURATION)
                            .coerceIn(0f, 1f)
                        -decaying.travelHeight + 2f * decaying.travelHeight * easeInQuad(down)
                    }

                    entity.x = decaying.originX
                    entity.y = decaying.originY + yOffset

                    val scaleProgress = (elapsed /
                        (HIT_FLY_UP_DURATION + HIT_FLY_DOWN_DURATION)).coerceIn(0f, 1f)
                    entity.setScale(1f + (HIT_FLY_SCALE - 1f) * scaleProgress)
                    entity.alpha = (1f - elapsed / HIT_FLY_DURATION).coerceIn(0f, 1f)
                }

                DecayMotion.HitBurst -> {
                    val scaleUpEnd = HIT_BURST_SCALE_UP
                    val scaleDownEnd = scaleUpEnd + HIT_BURST_SCALE_DOWN
                    val scaleSettleEnd = scaleDownEnd + HIT_BURST_SCALE_SETTLE

                    val scale = when {
                        elapsed < scaleUpEnd ->
                            HIT_BURST_START_SCALE +
                                (HIT_BURST_PEAK_SCALE - HIT_BURST_START_SCALE) *
                                (elapsed / scaleUpEnd)

                        elapsed < scaleDownEnd ->
                            HIT_BURST_PEAK_SCALE +
                                (HIT_BURST_DIP_SCALE - HIT_BURST_PEAK_SCALE) *
                                ((elapsed - scaleUpEnd) / HIT_BURST_SCALE_DOWN)

                        elapsed < scaleSettleEnd ->
                            HIT_BURST_DIP_SCALE +
                                (1f - HIT_BURST_DIP_SCALE) *
                                ((elapsed - scaleDownEnd) / HIT_BURST_SCALE_SETTLE)

                        else -> 1f
                    }

                    entity.setScale(scale)
                    entity.alpha = if (elapsed < HIT_BURST_FADE_IN) {
                        elapsed / HIT_BURST_FADE_IN
                    } else {
                        (1f - (elapsed - HIT_BURST_FADE_IN) / HIT_BURST_FADE_OUT)
                            .coerceIn(0f, 1f)
                    }
                }

                DecayMotion.RollTick -> {
                    entity.setScale(1f + 0.4f * progress)
                    entity.alpha = (1f - progress).coerceIn(0f, 1f)
                }

                DecayMotion.SwellClear -> {
                    entity.setScale(1f + 0.05f * progress)
                    entity.alpha = (1f - progress).coerceIn(0f, 1f)
                }
            }
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

            // Every object scrolls in from the right, dendens included. osu!lazer treats a swell
            // as an ordinary scrolling object that simply stops once it reaches the hit target.
            val spawnTime = obj.startTime - obj.preempt

            if (obj.entity == null && now >= spawnTime && now <= obj.endTime + missWindow) {
                val entity = createObjectEntity(obj)
                obj.entity = entity
                playfield.attachChild(entity)
            }

            obj.entity?.let { entity ->
                val x = targetX + ((obj.startTime - now) * obj.velocity).toFloat()

                when (obj.kind) {
                    ObjectKind.Denden -> {
                        // The swell travels in like any other object but stops dead on the hit
                        // target rather than sailing past it, so it stays under the player's
                        // fingers for the whole time they are alternating on it.
                        entity.x = x.coerceAtLeast(targetX) - entity.width / 2f
                        entity.y = laneY - entity.height / 2f
                        updateSwellVisuals(obj, now)
                    }

                    ObjectKind.Drumroll -> {
                        // A roll is anchored by its head rather than centred on its start time,
                        // so its body extends forward towards the end of the roll.
                        entity.x = x - obj.rollHeight / 2f
                        entity.y = laneY - entity.height / 2f
                    }

                    else -> {
                        entity.x = x - entity.width / 2f
                        entity.y = laneY - entity.height / 2f
                    }
                }
            }

            when (obj.kind) {
                ObjectKind.Don, ObjectKind.Kat -> {
                    if (now - obj.startTime > missWindow) {
                        registerMiss(obj)
                    }
                }

                ObjectKind.Drumroll -> {
                    // Give the final tick its full window before retiring the roll.
                    if (now > obj.endTime + obj.tickWindow) {
                        expire(obj)
                    }
                }

                ObjectKind.Denden -> {
                    if (now > obj.endTime) {
                        expireDenden(obj)
                    }
                }
            }
        }
    }

    /**
     * Drives stable's shaker presentation.
     *
     * `spinner-approachcircle` shrinks towards `spinner-circle` over the swell's duration, the
     * circle rotates anti-clockwise on each counted hit, the warning is shown only while the swell
     * is still approaching, and an outline ring fills as the counter goes down.
     */
    private fun updateSwellVisuals(obj: TaikoObject, now: Double) {
        val duration = (obj.endTime - obj.startTime).coerceAtLeast(1.0)
        val progress = ((now - obj.startTime) / duration).coerceIn(0.0, 1.0).toFloat()

        obj.swellApproachRing?.let { ring ->
            ring.setScale(
                SWELL_APPROACH_START_SCALE + (1f - SWELL_APPROACH_START_SCALE) * progress
            )
            ring.alpha = 0.65f
        }

        obj.swellCircle?.rotation = obj.swellRotation
        obj.swellWarning?.isVisible = now < obj.startTime

        obj.swellExpandingRing?.let { ring ->
            val completion = if (obj.requiredHits <= 0) {
                0f
            } else {
                (obj.hitsSoFar.toFloat() / obj.requiredHits).coerceIn(0f, 1f)
            }

            ring.setPortion(completion)
            ring.alpha = 0.85f
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
                    // Hit the note exactly at its start time, with both colours on a big note so
                    // autoplay collects the strong bonus the way a player would.
                    if (now >= obj.startTime) {
                        autoHitNote(obj, obj.kind == ObjectKind.Kat)
                    }
                }

                ObjectKind.Drumroll -> {
                    // Collect ticks as they come due rather than tapping on a fixed interval.
                    if (now >= obj.startTime) {
                        autoHitRoll(obj, now)
                    }
                }

                ObjectKind.Denden -> {
                    // Alternate colours, which is the only way a denden counter advances.
                    if (now in obj.startTime..obj.endTime) {
                        autoHitDenden(obj, now)
                    }
                }
            }
        }
    }

    private var lastAutoDendenTime = 0.0

    private fun autoHitNote(obj: TaikoObject, isKat: Boolean) {
        flashDrum(isKat)
        registerNoteHit(obj, isKat, bothColours = obj.isBig, now = obj.startTime)
    }

    private fun autoHitRoll(obj: TaikoObject, now: Double) {
        if (obj.nextTickIndex >= obj.tickTimes.size) {
            return
        }

        if (now < obj.tickTimes[obj.nextTickIndex]) {
            return
        }

        flashDrum(false)
        playInputSound(false, now)
        registerRollHit(obj, now)
    }

    private fun autoHitDenden(obj: TaikoObject, now: Double) {
        if (now - lastAutoDendenTime < AUTO_DENDEN_INTERVAL) {
            return
        }
        lastAutoDendenTime = now

        // Flip colour every hit; hitting the same colour twice would not count.
        val isKat = obj.lastHitKat != true
        flashDrum(isKat)
        playInputSound(isKat, now)
        registerDendenHit(obj, isKat, now)
    }

    private fun createObjectEntity(obj: TaikoObject): UIComponent = when (obj.kind) {
        ObjectKind.Don, ObjectKind.Kat -> createNoteEntity(obj)

        ObjectKind.Drumroll -> UIContainer().apply {
            // Stable draws a roll as `taiko-roll-middle` stretched across its duration, capped by
            // `taiko-roll-end` and headed by a tinted `taikobigcircle`. The small `sliderscorepoint`
            // markers are the only collectable parts of it.
            val height = if (obj.isBig) bigNoteDiameter else normalNoteDiameter
            obj.rollHeight = height

            val durationWidth = max(
                height,
                ((obj.endTime - obj.startTime) * obj.velocity).toFloat()
            )

            width = durationWidth + height
            this.height = height

            val middleTexture = TaikoSkin[TaikoSkinNames.ROLL_MIDDLE]
            val endTexture = TaikoSkin[TaikoSkinNames.ROLL_END]
            val headTexture = TaikoSkin[TaikoSkinNames.BIG_CIRCLE]
                ?: TaikoSkin[TaikoSkinNames.HIT_CIRCLE]

            obj.rollBody = if (middleTexture != null) {
                sprite {
                    x = height / 2f
                    y = 0f
                    width = durationWidth
                    this.height = height
                    scaleType = ScaleType.Stretch
                    textureRegion = middleTexture
                    color = ROLL_IDLE_COLOR
                    alpha = 0.9f
                }
            } else {
                box {
                    x = height / 2f
                    y = 0f
                    width = durationWidth
                    this.height = height
                    cornerRadius = height / 2f
                    color = ROLL_IDLE_COLOR
                    alpha = 0.9f
                }
            }

            if (endTexture != null) {
                sprite {
                    x = durationWidth
                    y = 0f
                    width = height
                    this.height = height
                    scaleType = ScaleType.Stretch
                    textureRegion = endTexture
                    color = ROLL_IDLE_COLOR
                }
            } else {
                circle {
                    x = durationWidth
                    y = 0f
                    width = height
                    this.height = height
                    color = ROLL_IDLE_COLOR
                }
            }

            obj.rollHead = if (headTexture != null) {
                sprite {
                    x = 0f
                    y = 0f
                    width = height
                    this.height = height
                    scaleType = ScaleType.Fit
                    textureRegion = headTexture
                    color = ROLL_HEAD_COLOR
                }
            } else {
                circle {
                    x = 0f
                    y = 0f
                    width = height
                    this.height = height
                    color = ROLL_HEAD_COLOR
                }
            }

            val tickTexture = TaikoSkin[TaikoSkinNames.ROLL_TICK]
            val tickSize = height * 0.28f

            obj.tickTimes.forEach { tickTime ->
                val tickX = (height / 2f + (tickTime - obj.startTime).toFloat() * obj.velocity.toFloat())
                    .coerceIn(height / 2f, durationWidth)

                val tick = if (tickTexture != null) {
                    sprite {
                        x = tickX - tickSize / 2f
                        y = (height - tickSize) / 2f
                        width = tickSize
                        this.height = tickSize
                        scaleType = ScaleType.Fit
                        textureRegion = tickTexture
                        origin = Anchor.Center
                    }
                } else {
                    circle {
                        x = tickX - tickSize / 2f
                        y = (height - tickSize) / 2f
                        width = tickSize
                        this.height = tickSize
                        color = Color4.White
                        alpha = 0.85f
                        origin = Anchor.Center
                    }
                }

                obj.tickSprites.add(tick)
            }
        }

        // Stable's shaker: `spinner-circle`, a shrinking `spinner-approachcircle`, a warning while
        // it approaches, a countdown and an outline ring that fills as the counter goes down.
        ObjectKind.Denden -> UIContainer().apply {
            width = swellBoxDiameter
            height = swellBoxDiameter
            origin = Anchor.Center

            val centre = swellBoxDiameter / 2f
            val approachTexture = TaikoSkin[TaikoSkinNames.SPINNER_APPROACH]
            val circleTexture = TaikoSkin[TaikoSkinNames.SPINNER_CIRCLE]
            val warningTexture = TaikoSkin[TaikoSkinNames.SPINNER_WARNING]

            obj.swellApproachRing = if (approachTexture != null) {
                sprite {
                    x = centre - spinnerDiameter / 2f
                    y = centre - spinnerDiameter / 2f
                    width = spinnerDiameter
                    height = spinnerDiameter
                    scaleType = ScaleType.Fit
                    textureRegion = approachTexture
                    origin = Anchor.Center
                    alpha = 0.65f
                }
            } else {
                circle {
                    x = centre - spinnerDiameter / 2f
                    y = centre - spinnerDiameter / 2f
                    width = spinnerDiameter
                    height = spinnerDiameter
                    color = Color4.White
                    alpha = 0.65f
                    paintStyle = PaintStyle.Outline
                    lineWidth = 3f
                    origin = Anchor.Center
                }
            }

            obj.swellExpandingRing = circle {
                val ringSize = spinnerDiameter * 1.25f
                x = centre - ringSize / 2f
                y = centre - ringSize / 2f
                width = ringSize
                height = ringSize
                color = Color4(0xFFFFD54F)
                alpha = 0.85f
                paintStyle = PaintStyle.Outline
                lineWidth = 6f
                startAngle = -90f
                setPortion(0f)
            }

            obj.swellCircle = if (circleTexture != null) {
                sprite {
                    x = centre - spinnerDiameter / 2f
                    y = centre - spinnerDiameter / 2f
                    width = spinnerDiameter
                    height = spinnerDiameter
                    scaleType = ScaleType.Fit
                    textureRegion = circleTexture
                    origin = Anchor.Center
                }
            } else {
                circle {
                    x = centre - spinnerDiameter / 2f
                    y = centre - spinnerDiameter / 2f
                    width = spinnerDiameter
                    height = spinnerDiameter
                    color = Color4(0xFFFFC107)
                    alpha = 0.9f
                    origin = Anchor.Center
                }
            }

            obj.swellWarning = if (warningTexture != null) {
                sprite {
                    x = 0f
                    y = 0f
                    width = swellBoxDiameter
                    height = laneHeight * 0.3f
                    scaleType = ScaleType.Fit
                    textureRegion = warningTexture
                }
            } else {
                text {
                    x = 0f
                    y = 0f
                    width = swellBoxDiameter
                    alignment = Anchor.TopCenter
                    font = resources.getFont("smallFont")
                    color = Color4(0xFFFFD54F)
                    text = "SPINNER!"
                }
            }

            obj.counterText = text {
                x = 0f
                y = centre - 20f
                width = swellBoxDiameter
                alignment = Anchor.TopCenter
                font = resources.getFont("middleFont")
                color = Color4.White
                text = obj.requiredHits.toString()
            }
        }
    }

    /**
     * Builds a don or kat note.
     *
     * Stable tints `taikohitcircle` / `taikobigcircle` with the don or kat colour, keeps the
     * (possibly animated) overlay on top from the start but only starts alternating its frames at
     * 50 combo, and flashes an additive copy of the body on the beat during kiai time.
     */
    private fun createNoteEntity(obj: TaikoObject): UIComponent {
        val diameter = if (obj.isBig) bigNoteDiameter else normalNoteDiameter
        val noteColor = if (obj.kind == ObjectKind.Kat) KAT_COLOR else DON_COLOR
        val body = TaikoSkin.noteBody(obj.isBig)
        val overlayFrames = TaikoSkin.noteOverlayFrames(obj.isBig)

        return UIContainer().apply {
            width = diameter
            height = diameter
            origin = Anchor.Center

            if (body != null) {
                obj.noteSprite = sprite {
                    x = 0f
                    y = 0f
                    width = diameter
                    height = diameter
                    scaleType = ScaleType.Fit
                    textureRegion = body
                    color = noteColor
                }

                obj.noteFlashSprite = sprite {
                    x = 0f
                    y = 0f
                    width = diameter
                    height = diameter
                    scaleType = ScaleType.Fit
                    textureRegion = body
                    color = Color4.White
                    alpha = 0f
                }
            } else {
                circle {
                    x = 0f
                    y = 0f
                    width = diameter
                    height = diameter
                    color = Color4.White
                }

                circle {
                    val inset = if (obj.isBig) 7f else 5f
                    x = inset
                    y = inset
                    width = diameter - inset * 2f
                    height = diameter - inset * 2f
                    color = noteColor
                }

                circle {
                    val centreSize = diameter * 0.27f
                    x = (diameter - centreSize) / 2f
                    y = (diameter - centreSize) / 2f
                    width = centreSize
                    height = centreSize
                    color = Color4.White
                    alpha = 0.78f
                }

                obj.noteFlashSprite = circle {
                    x = 0f
                    y = 0f
                    width = diameter
                    height = diameter
                    color = Color4.White
                    alpha = 0f
                }
            }

            if (overlayFrames.isNotEmpty()) {
                obj.noteOverlayFrames = overlayFrames.take(2).mapIndexed { index, region ->
                    sprite {
                        x = 0f
                        y = 0f
                        width = diameter
                        height = diameter
                        scaleType = ScaleType.Fit
                        textureRegion = region
                        isVisible = index == 0
                    }
                }
            } else {
                circle {
                    x = 0f
                    y = 0f
                    width = diameter
                    height = diameter
                    color = Color4.White
                    alpha = 0.55f
                    paintStyle = PaintStyle.Outline
                    lineWidth = diameter * 0.045f
                }
            }
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
        registerInput(isKat, currentTime)
        return true
    }

    /**
     * Judges a tap.
     *
     * osu!stable is strict about colour. Hitting a note with the wrong key — or hitting both keys
     * on a note that is not a big one — is a miss. A big note needs both keys together for its
     * double score, but a single correct key still connects for the ordinary value.
     */
    private fun registerInput(isKat: Boolean, now: Double) {
        flashDrum(isKat)
        pulseKiaiGlow(now)

        // Two taps of opposite colour landing this close together are one simultaneous hit.
        val bothColours = lastInputIsKat != null &&
            lastInputIsKat != isKat &&
            now - lastInputTime <= STRONG_INPUT_WINDOW

        lastInputTime = now
        lastInputIsKat = isKat

        // A big note struck with a single key can still be upgraded while the opposite colour is
        // arriving, which is what a player pressing both thumbs down actually produces.
        if (bothColours) {
            val pending = lastJudgedNote

            if (pending != null && pending.isBig && !pending.strongHit &&
                now - pending.judgedAt <= STRONG_INPUT_WINDOW
            ) {
                lastJudgedNote = null
                playInputSound(isKat, now)
                upgradeToStrongHit(pending)
                return
            }
        }

        playInputSound(isKat, now)

        // Denden first: it swallows every tap while it is on the field.
        val activeDenden = objects.firstOrNull {
            !it.judged && it.kind == ObjectKind.Denden && now in it.startTime..it.endTime
        }

        if (activeDenden != null) {
            registerDendenHit(activeDenden, isKat, now)
            return
        }

        val activeRoll = objects.firstOrNull {
            !it.judged &&
                it.kind == ObjectKind.Drumroll &&
                now in it.startTime..(it.endTime + it.tickWindow)
        }

        if (activeRoll != null) {
            registerRollHit(activeRoll, now)
            return
        }

        val candidate = objects
            .asSequence()
            .filter {
                !it.judged &&
                    (it.kind == ObjectKind.Don || it.kind == ObjectKind.Kat) &&
                    abs(now - it.startTime) <= missWindow
            }
            .minByOrNull { abs(now - it.startTime) }

        if (candidate == null) {
            return
        }

        registerNoteHit(candidate, isKat, bothColours, now)
    }

    /**
     * Applies osu!stable's ScoreV1 for osu!taiko:
     *
     * `Score = {ScoreValue + [min(RoundDown(Combo / 10), 10) * RoundDown(n * mod multiplier)]} * kiai`
     *
     * where `ScoreValue` is 300 for a GREAT and 150 for a GOOD, doubled on a big note. The combo
     * feeding it is the combo before this hit, minus one.
     */
    private fun scoreForHit(baseValue: Long, isKiai: Boolean): Long {
        val comboBeforeThisHit = (combo - 1).coerceAtLeast(0)
        val comboSteps = comboBeforeThisHit.coerceAtMost(COMBO_SCORE_LIMIT) / COMBO_SCORE_STEP
        val step = floor(taikoScoreMultiplier * modScoreMultiplier).toLong()

        val value = baseValue + comboSteps * step
        return (value * if (isKiai) KIAI_SCORE_MULTIPLIER else 1f).toLong()
    }

    private fun baseScoreValue(result: HitResult, isBig: Boolean): Long {
        val value = when (result) {
            HitResult.Great -> SCORE_VALUE_GREAT
            HitResult.Good -> SCORE_VALUE_GOOD
            HitResult.Miss -> 0L
        }
        return if (isBig) value * 2L else value
    }

    private fun registerNoteHit(obj: TaikoObject, isKat: Boolean, bothColours: Boolean, now: Double) {
        // osu!stable calls a wrong-colour tap a miss, and colour is the whole point of the mode:
        // without it every note could be cleared by mashing a single key.
        //
        // Stable additionally misses a note when both colours are struck at once on a note that
        // isn't big. That half of the rule is deliberately not replicated: this scene judges on the
        // first tap so that input stays latency-free, which means the "both keys" case is
        // indistinguishable from a fast alternating stream once the first tap has landed. Applying
        // it would punish legitimate play at high BPM.
        if (obj.kind == ObjectKind.Kat != isKat) {
            registerMiss(obj)
            return
        }

        val offset = abs(now - obj.startTime)
        val result = when {
            offset <= greatWindow -> HitResult.Great
            offset <= okWindow -> HitResult.Good
            else -> HitResult.Miss
        }

        if (result == HitResult.Miss) {
            registerMiss(obj)
            return
        }

        val isStrong = obj.isBig && bothColours
        val isKiai = isKiaiAt(now)

        val baseValue = baseScoreValue(result, isStrong)
        val gained = scoreForHit(baseValue, isKiai)

        obj.strongHit = isStrong
        obj.judgedIsKat = isKat
        obj.judgedOffset = offset
        obj.judgedAt = now
        obj.judgedScore = gained

        score += gained

        when (result) {
            HitResult.Great -> greatCount++
            HitResult.Good -> goodCount++
            HitResult.Miss -> missCount++
        }

        combo++
        maxCombo = max(maxCombo, combo)

        addHealth(
            when (result) {
                HitResult.Great -> HEALTH_PER_GREAT
                HitResult.Good -> HEALTH_PER_GOOD
                HitResult.Miss -> HEALTH_PER_MISS
            } * if (isStrong) 2f else 1f
        )

        isPassing = true
        pulseKiaiGlow(now)
        checkComboMilestone()
        showBurst(result, isStrong)
        playSamples(obj)
        releaseHit(obj)

        lastJudgedNote = obj
    }

    /**
     * Upgrades a big note that was struck with one key to a strong hit.
     *
     * A strong hit is worth exactly double what the single key paid, so the upgrade simply pays the
     * original amount again rather than recomputing the judgement.
     */
    private fun upgradeToStrongHit(obj: TaikoObject) {
        obj.strongHit = true
        score += obj.judgedScore

        addHealth(HEALTH_PER_GREAT)

        // Stable swaps in the `k` burst when the second key lands on a big note.
        isPassing = true
        showBurst(HitResult.Great, true)
    }

    /**
     * Handles a tap landing on a denden.
     *
     * osu!taiko only advances the counter when the colour alternates, so repeating the same colour
     * still plays and flashes but does not count. Dendens never touch the combo counter and never
     * heal, and each counted hit is worth a flat score regardless of timing.
     */
    private fun registerDendenHit(obj: TaikoObject, isKat: Boolean, now: Double) {
        pulseKiaiGlow(now)

        if (obj.lastHitKat == isKat) {
            return
        }

        // Stable rotates `spinner-circle` anti-clockwise for every counted hit.
        obj.swellRotation -= SWELL_ROTATION_PER_HIT
        obj.swellCircle?.rotation = obj.swellRotation

        obj.lastHitKat = isKat
        obj.hitsSoFar++
        score += DENDEN_HIT_SCORE

        val remaining = (obj.requiredHits - obj.hitsSoFar).coerceAtLeast(0)
        obj.counterText?.text = remaining.toString()

        if (obj.hitsSoFar >= obj.requiredHits) {
            completeDenden(obj, now)
        }
    }

    /**
     * Clears a denden. Completion is worth a GREAT big note, which is the one denden score kiai
     * does apply to. It still does not increment combo.
     */
    private fun completeDenden(obj: TaikoObject, now: Double) {
        score += scoreForHit(baseScoreValue(HitResult.Great, true), isKiaiAt(now))

        showJudgement("CLEAR!", Color4(0xFFFFD54F))
        setMascotState(MascotState.Clear, COMBO_MILESTONE_DURATION)
        isPassing = true

        obj.judged = true
        obj.counterText = null
        obj.swellCircle = null
        obj.swellApproachRing = null
        obj.swellExpandingRing = null
        obj.swellWarning = null

        obj.entity?.let { entity ->
            entity.origin = Anchor.Center
            decayingEntities.add(
                DecayingEntity(
                    entity,
                    DENDEN_CLEAR_DECAY_DURATION,
                    DENDEN_CLEAR_DECAY_DURATION,
                    motion = DecayMotion.SwellClear
                )
            )
        }
        obj.entity = null
    }

    /**
     * Retires an uncleared denden. Failing one costs health but never breaks combo, and it is not
     * counted as a miss because dendens do not contribute to accuracy in osu!taiko.
     */
    private fun expireDenden(obj: TaikoObject) {
        if (obj.hitsSoFar < obj.requiredHits) {
            addHealth(HEALTH_PER_DENDEN_FAIL)
            setMascotState(MascotState.Fail, MASCOT_FAIL_DURATION)
            isPassing = false
        }

        obj.counterText = null
        obj.swellCircle = null
        obj.swellApproachRing = null
        obj.swellExpandingRing = null
        obj.swellWarning = null
        expire(obj)
    }

    /**
     * Handles a tap landing on a drum roll.
     *
     * Drum rolls are not scored on raw tapping speed: they carry a fixed number of ticks, and a tap
     * only counts if it lands within half a tick spacing of the next uncollected tick. Tapping
     * faster than the ticks appear collects nothing extra, and tapping too slowly simply lets ticks
     * go by. Missed ticks carry no score penalty, but they do cool the body's colour back down.
     */
    private fun registerRollHit(obj: TaikoObject, now: Double) {
        pulseKiaiGlow(now)

        // Step past any ticks whose window has already closed.
        while (
            obj.nextTickIndex < obj.tickTimes.size &&
            now > obj.tickTimes[obj.nextTickIndex] + obj.tickWindow
        ) {
            obj.nextTickIndex++
            obj.rollingHits = (obj.rollingHits - 1).coerceAtLeast(0)
            updateRollColour(obj)
        }

        if (obj.nextTickIndex >= obj.tickTimes.size) {
            return
        }

        // Too early: the next tick has not opened yet.
        if (now < obj.tickTimes[obj.nextTickIndex] - obj.tickWindow) {
            return
        }

        val collectedIndex = obj.nextTickIndex
        obj.nextTickIndex++
        obj.ticksHit++
        obj.rollingHits = (obj.rollingHits + 1).coerceAtMost(ROLL_ENGAGED_HITS)
        updateRollColour(obj)
        score += if (obj.isBig) BIG_ROLL_TICK_SCORE else ROLL_TICK_SCORE
        animateRollTick(obj, collectedIndex)
    }

    /** Stable pops a collected `sliderscorepoint` to 1.4x and fades it out over 200ms. */
    private fun animateRollTick(obj: TaikoObject, index: Int) {
        val tick = obj.tickSprites.getOrNull(index) ?: return

        tick.origin = Anchor.Center
        decayingEntities.add(
            DecayingEntity(
                tick,
                ROLL_TICK_ANIM_DURATION,
                ROLL_TICK_ANIM_DURATION,
                motion = DecayMotion.RollTick
            )
        )
    }

    /**
     * Repaints a drum roll to match how well it is being played.
     *
     * osu!lazer ramps the body from an idle to an engaged colour over the last few ticks, which is
     * immediate feedback on whether the roll is actually being collected.
     */
    private fun updateRollColour(obj: TaikoObject) {
        val colour = rollColour(obj.rollingHits)

        obj.rollBody?.color = colour
        obj.rollHead?.color = colour
    }

    /** Blends between the idle and engaged drum roll colours. */
    private fun rollColour(rollingHits: Int): Color4 {
        val t = (rollingHits.toFloat() / ROLL_ENGAGED_HITS).coerceIn(0f, 1f)

        return Color4(
            ROLL_IDLE_COLOR.red + (ROLL_ENGAGED_COLOR.red - ROLL_IDLE_COLOR.red) * t,
            ROLL_IDLE_COLOR.green + (ROLL_ENGAGED_COLOR.green - ROLL_IDLE_COLOR.green) * t,
            ROLL_IDLE_COLOR.blue + (ROLL_ENGAGED_COLOR.blue - ROLL_IDLE_COLOR.blue) * t
        )
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
        lastJudgedNote = null
        addHealth(HEALTH_PER_MISS)

        // A miss swaps the scroller to `taiko-slider-fail` and plays pippidon's fail animation.
        isPassing = false
        setMascotState(MascotState.Fail, MASCOT_FAIL_DURATION)
        showBurst(HitResult.Miss, false)
        releaseMiss(obj)
    }

    private fun addHealth(units: Float) {
        health = (health + units * healthUnit).coerceIn(0f, 1f)
    }

    /** osu!stable celebrates a combo milestone every 50 hits rather than osu!'s 25. */
    private fun checkComboMilestone() {
        if (combo > 0 && combo % COMBO_MILESTONE_INTERVAL == 0) {
            milestoneTimeRemaining = COMBO_MILESTONE_DURATION
            setMascotState(MascotState.Clear, COMBO_MILESTONE_DURATION)
        }
    }

    /**
     * Fires stable's hit burst at the judgement circle.
     *
     * Stable uses `taiko-hit300`/`taiko-hit100`/`taiko-hit0`, swapping to the `k` sprite for a big
     * note's second hit. Static sprites get the legacy 120ms fade-in / 180ms fade-out and a quick
     * 0.6 -> 1.1 -> 0.9 -> 1 scale pop; animated bursts keep their own frames. When no skin
     * provides a burst, a ring plus the judgement value is drawn with the same animation.
     */
    private fun showBurst(result: HitResult, isStrong: Boolean) {
        val frames = TaikoSkin.burstFrames(result, isStrong)
        val ringColor = when (result) {
            HitResult.Great -> Color4(0xFFFFD54F)
            HitResult.Good -> Color4(0xFF81D4FA)
            HitResult.Miss -> Color4(0xFFB0BEC5)
        }
        val label = when (result) {
            HitResult.Great -> "300"
            HitResult.Good -> "100"
            HitResult.Miss -> "×"
        }

        val burst = UIContainer().apply {
            x = targetX - burstDiameter / 2f
            y = laneY - burstDiameter / 2f
            width = burstDiameter
            height = burstDiameter
            origin = Anchor.Center

            when {
                frames.size > 1 -> {
                    val animated = UIAnimatedSprite(
                        frames.map { it as TextureRegion? }.toTypedArray()
                    ).apply {
                        x = 0f
                        y = 0f
                        width = burstDiameter
                        height = burstDiameter
                        scaleType = ScaleType.Fit
                        frameTime = HIT_BURST_DURATION / frames.size.toFloat()
                        isLoop = false
                    }
                    attachChild(animated)
                }

                frames.size == 1 -> {
                    sprite {
                        x = 0f
                        y = 0f
                        width = burstDiameter
                        height = burstDiameter
                        scaleType = ScaleType.Fit
                        textureRegion = frames[0]
                    }
                }

                else -> {
                    circle {
                        x = 0f
                        y = 0f
                        width = burstDiameter
                        height = burstDiameter
                        color = ringColor
                        alpha = 0.85f
                        paintStyle = PaintStyle.Outline
                        lineWidth = burstDiameter * 0.08f
                    }

                    text {
                        x = 0f
                        y = burstDiameter / 2f - 20f
                        width = burstDiameter
                        alignment = Anchor.TopCenter
                        font = resources.getFont("middleFont")
                        color = ringColor
                        text = label
                    }
                }
            }
        }

        playfield.attachChild(burst)
        decayingEntities.add(
            DecayingEntity(
                burst,
                HIT_BURST_DURATION,
                HIT_BURST_DURATION,
                motion = DecayMotion.HitBurst
            )
        )
    }

    /**
     * Judged as a hit: the note snaps onto the judgement circle, rises over 300ms, falls over the
     * next 600ms, shrinks to 0.8 and fades out over 800ms. Stable always launches the note from the
     * centre of the target, which avoids a smear trailing behind the drum at high scroll speeds.
     */
    private fun releaseHit(obj: TaikoObject) {
        obj.judged = true
        obj.noteSprite = null
        obj.noteOverlayFrames = emptyList()
        obj.noteFlashSprite = null

        obj.entity?.let { entity ->
            val startX = targetX - entity.width / 2f
            val startY = laneY - entity.height / 2f

            entity.origin = Anchor.Center
            entity.x = startX
            entity.y = startY

            decayingEntities.add(
                DecayingEntity(
                    entity,
                    HIT_FLY_DURATION,
                    HIT_FLY_DURATION,
                    motion = DecayMotion.HitFly,
                    originX = startX,
                    originY = startY,
                    travelHeight = laneHeight
                )
            )
        }
        obj.entity = null
    }

    /** Judged as a miss: the note keeps scrolling at its own speed while it fades over 100ms. */
    private fun releaseMiss(obj: TaikoObject) {
        obj.judged = true
        obj.noteSprite = null
        obj.noteOverlayFrames = emptyList()
        obj.noteFlashSprite = null

        obj.entity?.let { entity ->
            decayingEntities.add(
                DecayingEntity(
                    entity,
                    MISS_DECAY_DURATION,
                    MISS_DECAY_DURATION,
                    motion = DecayMotion.Miss,
                    velocityX = -(obj.velocity * 1000.0).toFloat()
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
        pauseDialog.show()
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

        // osu!taiko only passes once the bar is at least half full.
        val passed = health >= HEALTH_PASS_THRESHOLD

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

        Log.i("TaikoGameScene", "osu!taiko beta run finished (passed=$passed, health=$health)")

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

    private fun restart() {
        pauseDialog.hide()
        loadFailedDialog.hide()
        cleanup()
        start(beatmapInfo, mods)
    }

    /** Cancels async beatmap loading, used by GameLoaderScene's back button via the adapter. */
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
