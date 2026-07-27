package com.osudroid.ui.v2.hud.elements

import com.osudroid.ui.v2.hud.HUDElement
import com.osudroid.ui.v2.SpriteFont
import com.rian.framework.RollingLongCounter
import ru.nsu.ccfit.zuev.osu.game.GameScene
import ru.nsu.ccfit.zuev.skins.OsuSkin
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class HUDScoreCounter : HUDElement() {

    private val sprite = SpriteFont(OsuSkin.get().scorePrefix)
    private val format = DecimalFormat("00000000", DecimalFormatSymbols(Locale.US))

    private var value = 0L
        set(value) {
            if (field != value) {
                field = value
                sprite.text = format.format(value)
            }
        }

    private val counter = RollingLongCounter(0L).apply { rollingDuration = 1f }

    init {
        sprite.spacing = -OsuSkin.get().scoreOverlap

        val digitRange = '0'..'9'
        val maxDigitWidth = digitRange.maxOfOrNull { sprite.characters[it]?.width?.toFloat() ?: 0f } ?: 0f

        sprite.fixedCharWidths = digitRange.associateWith { maxDigitWidth }
        sprite.text = format.format(0)

        registerUpdateHandler(counter)
        attachChild(sprite)
        onContentChanged()
    }

    override fun onGameplayUpdate(game: GameScene, secondsElapsed: Float) {
        setScore(game.stat.totalScoreWithMultiplier)
    }

    /**
     * Updates the displayed score without requiring a legacy [GameScene].
     *
     * This keeps the skinnable HUD element reusable by isolated gameplay scenes.
     */
    fun setScore(score: Long) {
        counter.targetValue = score.coerceAtLeast(0L)
    }

    override fun onManagedUpdate(deltaTimeSec: Float) {
        value = counter.currentValue

        super.onManagedUpdate(deltaTimeSec)
    }
}
