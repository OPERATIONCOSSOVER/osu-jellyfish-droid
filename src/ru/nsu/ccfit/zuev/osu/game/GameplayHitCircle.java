package ru.nsu.ccfit.zuev.osu.game;

import com.edlplan.framework.easing.Easing;
import com.edlplan.framework.math.FMath;
import com.osudroid.utils.Execution;
import com.reco1l.andengine.UIScene;
import com.reco1l.andengine.sprite.UISprite;
import com.reco1l.andengine.Anchor;
import com.osudroid.ui.v2.game.NumberedCirclePiece;
import com.reco1l.framework.Color4;
import com.osudroid.beatmaps.HitWindow;
import com.osudroid.beatmaps.constants.HitObjectType;
import com.osudroid.beatmaps.hitobjects.HitCircle;
import com.osudroid.game.GameplayHitSampleInfo;
import com.osudroid.mods.ModHidden;
import com.osudroid.mods.ModObjectScaleTween;
import com.osudroid.mods.ModWiggle;

import java.util.ArrayList;

import ru.nsu.ccfit.zuev.osu.Config;
import ru.nsu.ccfit.zuev.osu.Constants;
import ru.nsu.ccfit.zuev.osu.ResourceManager;
import ru.nsu.ccfit.zuev.osu.scoring.ResultType;
import ru.nsu.ccfit.zuev.skins.OsuSkin;

public class GameplayHitCircle extends GameObject {

    /**
     * Converts an osu!pixels offset to screen space. The Wiggle mod works in osu!pixels so that it
     * does not need to know how the playfield is laid out.
     */
    private static final float OSU_PIXEL_TO_SCREEN_X = (float) Constants.MAP_ACTUAL_WIDTH / Constants.MAP_WIDTH;
    private static final float OSU_PIXEL_TO_SCREEN_Y = (float) Constants.MAP_ACTUAL_HEIGHT / Constants.MAP_HEIGHT;

    private final UISprite approachCircle;
    private Color4 comboColor = new Color4();
    private GameObjectListener listener;
    private UIScene scene;
    private HitCircle beatmapCircle;
    private float passedTime;
    private float timePreempt;
    private float hitOffset;
    private boolean kiai;
    private boolean successfulHit;
    private final ArrayList<GameplayHitSampleInfo> hitSamples = new ArrayList<>(5);

    /**
     * The precomputed Wiggle movement of this circle, or {@code null} if the mod is not enabled.
     */
    private ModWiggle.WiggleTrail wiggleTrail;

    /**
     * The position this circle would occupy if it were not wiggling.
     */
    private float wiggleOriginX;
    private float wiggleOriginY;

    /**
     * The circle piece that represents the circle body and overlay.
     */
    private final NumberedCirclePiece circlePiece;


    public GameplayHitCircle() {
        circlePiece = new NumberedCirclePiece("hitcircle", "hitcircleoverlay");
        approachCircle = new UISprite();
        approachCircle.setOrigin(Anchor.Center);
    }

    public void init(final GameObjectListener listener, final UIScene pScene, final HitCircle beatmapCircle,
                     final Color4 comboColor) {
        // Storing parameters into fields
        this.beatmapCircle = beatmapCircle;
        replayObjectData = null;

        var stackedPosition = beatmapCircle.getScreenSpaceGameplayStackedPosition();
        position.set(stackedPosition.x, stackedPosition.y);

        endsCombo = beatmapCircle.isLastInCombo();
        this.listener = listener;
        scene = pScene;
        timePreempt = (float) beatmapCircle.timePreempt / 1000;

        // Circles have no duration, so they only wiggle while they are approaching.
        var wiggle = GameHelper.getWiggle(listener);

        if (wiggle != null) {
            wiggleTrail = wiggle.createTrail(beatmapCircle.startTime, beatmapCircle.timePreempt, 0);
            wiggleOriginX = this.position.x;
            wiggleOriginY = this.position.y;
        } else {
            wiggleTrail = null;
        }

        float mehWindow = (float) beatmapCircle.hitWindow.getMehWindow() / 1000;
        hitOffset = mehWindow;

        hitTime = (float) beatmapCircle.startTime / 1000;
        passedTime = -timePreempt;
        startHit = false;
        successfulHit = false;
        kiai = GameHelper.isKiai();
        this.comboColor = comboColor;

        float initialModifierTime = hitTime - timePreempt;
        float scale = beatmapCircle.getScreenSpaceGameplayScale();
        float fadeInDuration = (float) beatmapCircle.timeFadeIn / 1000f;

        // Grow and Deflate are mutually incompatible, so at most one of these can be active.
        final ModObjectScaleTween scaleTween = GameHelper.getObjectScaleTween(listener);

        // Initializing sprites
        circlePiece.setCircleColor(comboColor);
        circlePiece.setScale(scale);
        circlePiece.setAlpha(0);
        circlePiece.setPosition(this.position.x, this.position.y);

        int comboNum = beatmapCircle.getIndexInCurrentCombo() + 1;
        if (OsuSkin.get().isLimitComboTextLength()) {
            comboNum %= 10;
        }

        circlePiece.setNumberText(comboNum);
        circlePiece.setNumberScale(OsuSkin.get().getComboTextScale());
        circlePiece.setVisible(!GameHelper.isTraceable() ||
                (Config.isShowFirstApproachCircle() && GameHelper.getTraceable().getFirstObject() == beatmapCircle));

        approachCircle.setColor(comboColor);
        approachCircle.setScale(scale * 3 * (float) (beatmapCircle.timePreempt / GameHelper.getOriginalTimePreempt()));
        approachCircle.setAlpha(0);
        approachCircle.setPosition(this.position.x, this.position.y);

        // Grow and Deflate hide the approach circle, since judging the size of the object is the
        // whole point of them. This mirrors IHidesApproachCircles in osu!lazer.
        approachCircle.setVisible(scaleTween == null && (!GameHelper.isHidden() ||
                (Config.isShowFirstApproachCircle() && GameHelper.getHidden().getFirstObject() == beatmapCircle)));

        approachCircle.setTextureRegion(ResourceManager.getInstance().getTexture(
                GameHelper.isTraceable() ? "defaultapproachcircle" : "approachcircle"));

        scene.attachChild(circlePiece, 0);
        scene.attachChild(approachCircle);

        boolean fadeOutCircle = GameHelper.isHidden() && !GameHelper.getHidden().isOnlyFadeApproachCircles();

        circlePiece.beginAbsoluteSequence(initialModifierTime, sequence -> {
            sequence.fadeIn(fadeInDuration);

            if (fadeOutCircle) {
                float fadeOutDuration = timePreempt * (float) ModHidden.FADE_OUT_DURATION_MULTIPLIER;
                sequence.then().fadeOut(fadeOutDuration);
            }
        });

        // The circle starts off at a different size and settles on its normal size exactly as it
        // becomes hittable.
        //
        // This is deliberately registered as its own sequence rather than being chained onto the
        // fade sequence above: appending it there would make Hidden's .then() fade out wait for the
        // full preempt duration instead of the fade in, throwing off its timing.
        if (scaleTween != null) {
            circlePiece.setScale(scale * scaleTween.getStartScale());

            circlePiece.beginAbsoluteSequence(initialModifierTime, sequence -> sequence
                    .scaleTo(scale * scaleTween.getEndScale(), timePreempt, Easing.OutCubic));
        }

        if (!fadeOutCircle && circlePiece.isVisible()) {
            float okWindow = (float) beatmapCircle.hitWindow.getOkWindow() / 1000;

            circlePiece.beginAbsoluteSequence(hitTime + okWindow,
                    sequence -> sequence.fadeOut(mehWindow - okWindow));
        }

        if (approachCircle.isVisible()) {
            Easing easing;
            var approachDifferentMod = GameHelper.getApproachDifferent();

            if (approachDifferentMod != null) {
                approachCircle.setScale(scale * approachDifferentMod.getScale());
                easing = approachDifferentMod.getEasing();
            } else {
                easing = Easing.None;
            }

            approachCircle.beginAbsoluteSequence(initialModifierTime, sequence -> sequence
                    .fadeTo(0.9f, Math.min(fadeInDuration * 2, timePreempt))
                    .scaleTo(scale, timePreempt, easing)
                    .after(e -> e.setAlpha(0)));
        }

        if (Config.isDimHitObjects() && circlePiece.isVisible()) {
            // Source: https://github.com/peppy/osu/blob/60271fb0f7e091afb754455f93180094c63fc3fb/osu.Game.Rulesets.Osu/Objects/Drawables/DrawableOsuHitObject.cs#L101
            var colorDim = 195f / 255f;

            circlePiece.setColor(colorDim, colorDim, colorDim);
            circlePiece.beginAbsoluteSequence(hitTime - (float) HitWindow.MISS_WINDOW / 1000,
                    sequence -> sequence.colorTo(1, 1, 1, 0.1f));
        }

        // Initialize samples
        var parsedSamples = beatmapCircle.getSamples();
        hitSamples.ensureCapacity(parsedSamples.size());

        for (int i = 0, size = parsedSamples.size(); i < size; i++) {
            var gameplaySample = GameplayHitSampleInfo.obtain();
            gameplaySample.init(parsedSamples.get(i));

            if (GameHelper.isSamplesMatchPlaybackRate()) {
                gameplaySample.setFrequency(GameHelper.getSpeedMultiplier());
            }

            hitSamples.add(gameplaySample);
        }

        setLifetimeEnd(Float.MAX_VALUE);
    }

    private void removeFromScene() {
        if (scene == null) {
            return;
        }

        setLifetimeEnd(hitTime + hitOffset);

        for (int i = hitSamples.size() - 1; i >= 0; --i) {
            hitSamples.get(i).release();
        }

        hitSamples.clear();
        circlePiece.clearEntityModifiers();
        approachCircle.clearEntityModifiers();
        approachCircle.detachSelf();

        if (successfulHit || !circlePiece.isVisible() || circlePiece.getAlpha() == 0) {
            circlePiece.detachSelf();
        } else {
            extendLifetime(circlePiece.fadeOut(0.1f).after(e -> Execution.updateThread(e::detachSelf)));
        }

        scene = null;
    }

    private void playHitSamples() {
        listener.playHitSamples(hitSamples);
    }

    /**
     * Moves this circle to where the Wiggle mod says it should be right now.
     * <p>
     * The offset is applied to {@link #position} and not just to the sprites, because that is what
     * hit detection reads. In osu!lazer the drawable itself is moved, so its hit area moves with it.
     */
    private void applyWiggle() {
        if (wiggleTrail == null) {
            return;
        }

        wiggleTrail.computeOffsetAt(listener.getElapsedTime() * 1000d);

        position.set(
            wiggleOriginX + wiggleTrail.offsetX * OSU_PIXEL_TO_SCREEN_X,
            wiggleOriginY + wiggleTrail.offsetY * OSU_PIXEL_TO_SCREEN_Y
        );

        circlePiece.setPosition(position.x, position.y);
        approachCircle.setPosition(position.x, position.y);
    }

    @Override
    public void update(final float dt) {
        if (beatmapCircle.hitWindow == null) {
            // Circle somehow does not have a judgement window - abandon.
            return;
        }

        if (isJudged()) {
            return;
        }

        passedTime = listener.getElapsedTime() - hitTime;

        // Applied before hit detection so that the hit area matches what is on screen this frame.
        applyWiggle();

        double mehWindow = beatmapCircle.hitWindow.getMehWindow() / 1000;

        // If we have clicked circle
        if (replayObjectData != null) {
            if (passedTime + dt / 2 > replayObjectData.accuracy / 1000f) {
                hitOffset = replayObjectData.accuracy / 1000f;
                listener.registerAccuracy(HitObjectType.Normal, hitOffset);
                startHit = true;
                successfulHit = Math.abs(hitOffset) <= mehWindow;
                // Remove circle and register hit in update thread
                listener.onCircleHit(id, hitOffset, position, endsCombo, replayObjectData.result, comboColor);
                if (successfulHit && !listener.isAfterSeek()) {
                    playHitSamples();
                }
                removeFromScene();
                return;
            }
        } else if (!autoPlay && listener.isObjectHittable(this)) {
            var hittingCursor = getHittingCursor(listener, beatmapCircle, passedTime);

            if (hittingCursor != null) {
                hitOffset = (float) (hittingCursor.getHitTime() - beatmapCircle.startTime) / 1000;
                listener.registerAccuracy(HitObjectType.Normal, hitOffset);
                startHit = true;
                successfulHit = Math.abs(hitOffset) <= mehWindow;
                // Remove circle and register hit in update thread
                listener.onCircleHit(id, hitOffset, position, endsCombo, (byte) 0, comboColor);
                if (successfulHit) {
                    playHitSamples();
                }
                removeFromScene();
                return;
            }
        }

        if (circlePiece.isVisible()) {
            if (GameHelper.isKiai()) {
                var kiaiModifier = (float) Math.max(0, 1 - GameHelper.getCurrentBeatTime() / GameHelper.getBeatLength()) * 0.5f;
                var r = Math.min(1, comboColor.getRed() + (1 - comboColor.getRed()) * kiaiModifier);
                var g = Math.min(1, comboColor.getGreen() + (1 - comboColor.getGreen()) * kiaiModifier);
                var b = Math.min(1, comboColor.getBlue() + (1 - comboColor.getBlue()) * kiaiModifier);
                kiai = true;
                circlePiece.setCircleColor(r, g, b);
            } else if (kiai) {
                circlePiece.setCircleColor(comboColor);
                kiai = false;
            }
        }

        // We are still at approach time. Let entity modifiers finish first.
        if (passedTime < 0) {
            return;
        }

        if (autoPlay) {
            // Remove circle and register hit in update thread
            hitOffset = 0;
            listener.registerAccuracy(HitObjectType.Normal, 0);
            listener.onCircleHit(id, 0, position, endsCombo, ResultType.HIT300.getId(), comboColor);
            startHit = true;
            successfulHit = true;
            if (!listener.isAfterSeek()) {
                playHitSamples();
            }
            removeFromScene();
        } else {
            approachCircle.clearEntityModifiers();
            approachCircle.setAlpha(1 - FMath.clamp(passedTime / 0.05f, 0, 1));

            // If passed too much time, counting it as miss
            if (passedTime > mehWindow) {
                startHit = true;
                final byte forcedScore = (replayObjectData == null) ? 0 : replayObjectData.result;

                removeFromScene();
                listener.registerAccuracy(HitObjectType.Normal, mehWindow + 1);
                listener.onCircleHit(id, 10, position, false, forcedScore, comboColor);
            }
        }
    }

    @Override
    public void onExpire() {
        circlePiece.clearEntityModifiers();
        approachCircle.clearEntityModifiers();

        circlePiece.detachSelf();
        approachCircle.detachSelf();

        for (int i = hitSamples.size() - 1; i >= 0; --i) {
            hitSamples.get(i).release();
        }

        hitSamples.clear();

        GameObjectPool.getInstance().putCircle(this);
    }

    @Override
    public boolean isJudged() {
        return startHit;
    }
}
