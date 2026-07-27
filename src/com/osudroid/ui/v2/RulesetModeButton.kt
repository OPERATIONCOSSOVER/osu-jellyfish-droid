package com.osudroid.ui.v2

import com.reco1l.andengine.ui.UITextButton
import ru.nsu.ccfit.zuev.osu.Config
import ru.nsu.ccfit.zuev.osu.ResourceManager

/**
 * PC-style song-select mode button.
 *
 * The callback validates and applies the next ruleset, then rebuilds song select.
 */
class RulesetModeButton(private val onRulesetChanged: Runnable) : UITextButton() {
    init {
        width = 310f
        height = 62f
        font = ResourceManager.getInstance().getFont("middleFont")
        updateLabel()

        onActionUp = {
            onRulesetChanged.run()
        }
    }

    private fun updateLabel() {
        text = "Mode: ${Config.getRulesetMode().displayName}"
    }
}
