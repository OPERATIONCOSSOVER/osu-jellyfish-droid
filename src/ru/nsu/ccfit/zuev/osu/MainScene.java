package ru.nsu.ccfit.zuev.osu;

import static com.acivev.ui.EffectKt.addFireworksWithPeriod;
import static com.acivev.ui.EffectKt.addSnowfallWithPeriod;

import android.content.Context;
import android.content.Intent;
import android.graphics.PointF;
import android.net.Uri;
import android.util.Log;

import com.acivev.VibratorManager;
import com.edlplan.framework.easing.Easing;
import com.osudroid.beatmaps.BeatmapCache;
import com.osudroid.utils.Execution;
import com.reco1l.andengine.Anchor;
import com.reco1l.andengine.UIScene;
import com.reco1l.andengine.shape.UIBox;
import com.reco1l.andengine.sprite.UISprite;
import com.osudroid.ui.BannerManager;
import com.osudroid.ui.BannerManager.BannerSprite;
import com.osudroid.data.BeatmapInfo;
import com.osudroid.ui.MainMenu;
import com.osudroid.ui.SeasonalBackgroundManager;
import com.osudroid.ui.ThemeSongManager;

import com.osudroid.beatmaplisting.BeatmapListing;
import com.reco1l.andengine.ui.UIConfirmDialog;
import com.reco1l.framework.Color4;
import com.reco1l.osu.ui.HorizontalMessageDialog;
import com.osudroid.beatmaps.timings.EffectControlPoint;
import com.osudroid.beatmaps.timings.TimingControlPoint;

import org.anddev.andengine.engine.handler.IUpdateHandler;
import org.anddev.andengine.entity.IEntity;
import org.anddev.andengine.entity.modifier.IEntityModifier;
import org.anddev.andengine.entity.modifier.MoveXModifier;
import org.anddev.andengine.entity.modifier.ParallelEntityModifier;
import org.anddev.andengine.entity.modifier.RotationModifier;
import org.anddev.andengine.entity.modifier.SequenceEntityModifier;
import org.anddev.andengine.entity.particle.ParticleSystem;
import org.anddev.andengine.entity.particle.emitter.PointParticleEmitter;
import org.anddev.andengine.entity.particle.initializer.AccelerationInitializer;
import org.anddev.andengine.entity.particle.initializer.RotationInitializer;
import org.anddev.andengine.entity.particle.initializer.VelocityInitializer;
import org.anddev.andengine.entity.particle.modifier.AlphaModifier;
import org.anddev.andengine.entity.particle.modifier.ExpireModifier;
import org.anddev.andengine.entity.particle.modifier.ScaleModifier;
import org.anddev.andengine.entity.primitive.Rectangle;
import org.anddev.andengine.entity.scene.background.ColorBackground;
import org.anddev.andengine.entity.scene.background.SpriteBackground;
import org.anddev.andengine.entity.sprite.Sprite;
import org.anddev.andengine.entity.text.ChangeableText;
import org.anddev.andengine.entity.text.Text;
import org.anddev.andengine.input.touch.TouchEvent;
import org.anddev.andengine.opengl.texture.region.TextureRegion;
import org.anddev.andengine.util.Debug;
import org.anddev.andengine.util.HorizontalAlign;
import org.anddev.andengine.util.modifier.IModifier;
import org.anddev.andengine.util.modifier.ease.EaseBounceOut;
import org.anddev.andengine.util.modifier.ease.EaseExponentialOut;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.opengles.GL10;

import ru.nsu.ccfit.zuev.audio.BassSoundProvider;
import ru.nsu.ccfit.zuev.audio.Status;
import ru.nsu.ccfit.zuev.osu.game.LinearSongProgress;
import ru.nsu.ccfit.zuev.osu.online.OnlineManager;
import ru.nsu.ccfit.zuev.osu.online.OnlinePanel;
import ru.nsu.ccfit.zuev.osu.online.OnlineScoring;
import ru.nsu.ccfit.zuev.osu.scoring.Replay;
import ru.nsu.ccfit.zuev.osu.scoring.ScoringScene;
import ru.nsu.ccfit.zuev.osu.scoring.StatisticV2;
import ru.nsu.ccfit.zuev.osuplus.BuildConfig;

/**
 * Created by Fuuko on 2015/4/24.
 */
public class MainScene implements IUpdateHandler {

    /**
     * How long the outgoing background takes to fade away, in seconds.
     */
    private static final float BACKGROUND_FADE_DURATION = 1.5f;

    public LinearSongProgress progressBar;
    public BeatmapInfo beatmapInfo;
    private Context context;
    private Sprite logo, logoOverlay, background, lastBackground;
    private Sprite music_nowplay;
    private UIScene scene;
    private ChangeableText musicInfoText;
    private final Rectangle[] spectrum = new Rectangle[120];
    private final float[] peakLevel = new float[120];
    private final float[] peakDownRate = new float[120];
    private final float[] peakAlpha = new float[120];
    private LinkedList<TimingControlPoint> timingControlPoints;
    private LinkedList<EffectControlPoint> effectControlPoints;
    private TimingControlPoint currentTimingPoint;
    private EffectControlPoint currentEffectPoint;

    private int particleBeginTime = 0;
    private boolean particleEnabled = false;
    private boolean isContinuousKiai = false;

    private final ParticleSystem[] particleSystem = new ParticleSystem[2];

    private boolean musicStarted;
    private BassSoundProvider hitsound;

    private double bpmLength = 1000;
    private double beatPassTime = 0;
    private boolean doChange = false;
    private boolean doStop = false;
    private long lastHit = 0;
    public boolean isOnExitAnim = false;

    private boolean isMenuShowed = false;
    private boolean doMenuShow = false;
    private float showPassTime = 0;
    private float menuBarX = 0;

    /**
     * Seconds the current seasonal background has been on screen for.
     */
    private float seasonalSlideTime = 0;

    private MainMenu menu;

    public void load(Context context) {
        this.context = context;
        Debug.i("Load: mainMenuLoaded()");
        VibratorManager.INSTANCE.init(context);
        scene = new UIScene();

        final TextureRegion tex = getMenuBackgroundTexture();

        if (tex != null) {
            float height = tex.getHeight();
            height *= Config.getRES_WIDTH()
                    / (float) tex.getWidth();
            final Sprite menuBg = new Sprite(
                    0,
                    (Config.getRES_HEIGHT() - height) / 2,
                    Config.getRES_WIDTH(), height, tex);
            scene.setBackground(new SpriteBackground(menuBg));
        } else {
            scene.setBackground(new ColorBackground(70 / 255f, 129 / 255f,
                    252 / 255f));
        }
        lastBackground = new Sprite(0, 0, Config.getRES_WIDTH(), Config.getRES_HEIGHT(), ResourceManager.getInstance().getTexture("emptyavatar"));

        addSnowfallWithPeriod(scene, context);
        addFireworksWithPeriod(scene, context);

        final TextureRegion logotex = ResourceManager.getInstance().getTexture("logo");
        logo = new Sprite((float) Config.getRES_WIDTH() / 2 - (float) logotex.getWidth() / 2, (float) Config.getRES_HEIGHT() / 2 - (float) logotex.getHeight() / 2, logotex) {
            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent,
                                         final float pTouchAreaLocalX,
                                         final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) {
                    if (hitsound != null) {
                        hitsound.play();
                    }
                    Debug.i("logo down");
                    return true;
                }
                if (pSceneTouchEvent.isActionUp()) {
                    Debug.i("logo up");
                    Debug.i("doMenuShow " + doMenuShow + " isMenuShowed " + isMenuShowed + " showPassTime " + showPassTime);
                    if (doMenuShow && isMenuShowed) {
                        showPassTime = 20000;
                    }
                    if (!doMenuShow && !isMenuShowed && logo.getX() == (Config.getRES_WIDTH() - logo.getWidth()) / 2) {
                        doMenuShow = true;
                        showPassTime = 0;
                    }
                    Debug.i("doMenuShow " + doMenuShow + " isMenuShowed " + isMenuShowed + " showPassTime " + showPassTime);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX,
                        pTouchAreaLocalY);
            }
        };

        logoOverlay = new Sprite((float) Config.getRES_WIDTH() / 2 - (float) logotex.getWidth() / 2, (float) Config.getRES_HEIGHT() / 2 - (float) logotex.getHeight() / 2, logotex);
        logoOverlay.setScale(1.07f);
        logoOverlay.setAlpha(0.2f);

        menu = new MainMenu(this);

        UIBox box = new UIBox() {

            {
                Text versionText = new Text(10f, 2f, ResourceManager.getInstance().getFont("smallFont"), "osu!jellyfish " + BuildConfig.VERSION_NAME);
                attachChild(versionText);

                setSize(versionText.getWidth() + 20f, versionText.getHeight() + 4f);
                setPosition(10f, Config.getRES_HEIGHT() - getHeight() - 10f);
                setColor(0f, 0f, 0f, 0.5f); // Black
                setCornerRadius(12f);
            }

            public boolean onAreaTouched(TouchEvent event, float localX, float localY) {
                if (event.isActionUp()) {
                    new HorizontalMessageDialog()
                        .setTitle("About")
                        .setMessage(
                                "<h1>osu!jellyfish</h1>\n" +
                                "<h5>Version " + BuildConfig.VERSION_NAME + "</h5>\n" +
                                "<p>osu!jellyfish, based on osu!droid<br>osu! is © peppy 2007-2026</p>\n" +
                                "<br>\n" +
                                "<a href=\"https://osu.ppy.sh\">Visit official osu! website ↗</a>\n" +
                                "<br>\n" +
                                "<br>\n" +
                                "<a href=\"https://osudroid.moe\">Visit official osu!droid website ↗</a>\n" +
                                "<br>\n" +
                                "<br>\n" +
                                "<a href=\"https://discord.gg/nyD92cE\">Join the official Discord server ↗</a>\n",
                            true
                        )
                        .addButton("Changelog", dialog -> {
                            dialog.dismiss();

                            try {
                                var intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://osudroid.moe/changelog/latest"));
                                context.startActivity(intent);
                            } catch (Exception e) {
                                android.util.Log.e("MainScene", "Failed to load changelog", e);
                            }

                            return null;
                        })
                        .addButton("Close", dialog -> {
                            dialog.dismiss();
                            return null;
                        })
                        .show();
                }
                return true;
            }
        };
        scene.attachChild(box);

        final Sprite music_prev = new Sprite(Config.getRES_WIDTH() - 50 * 6 + 35,
                47, 40, 40, ResourceManager.getInstance().getTexture(
                "music_prev")) {

            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent,
                                         final float pTouchAreaLocalX,
                                         final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) {
                    setColor(0.7f, 0.7f, 0.7f);
                    doChange = true;
                    return true;
                }
                if (pSceneTouchEvent.isActionUp()) {
                    setColor(1, 1, 1);
                    if (lastHit == 0) {
                        lastHit = System.currentTimeMillis();
                    } else {
                        if (System.currentTimeMillis() - lastHit <= 1000 && !isOnExitAnim) {
                            return true;
                        }
                    }
                    lastHit = System.currentTimeMillis();
                    musicControl(MusicOption.PREV);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX,
                        pTouchAreaLocalY);
            }
        };

        final Sprite music_play = new Sprite(Config.getRES_WIDTH() - 50 * 5 + 35,
                47, 40, 40, ResourceManager.getInstance().getTexture(
                "music_play")) {

            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent,
                                         final float pTouchAreaLocalX,
                                         final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) {
                    setColor(0.7f, 0.7f, 0.7f);
                    return true;
                }
                if (pSceneTouchEvent.isActionUp()) {
                    setColor(1, 1, 1);
                    musicControl(MusicOption.PLAY);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX,
                        pTouchAreaLocalY);
            }
        };

        final Sprite music_pause = new Sprite(Config.getRES_WIDTH() - 50 * 4 + 35,
                47, 40, 40, ResourceManager.getInstance().getTexture(
                "music_pause")) {

            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent,
                                         final float pTouchAreaLocalX,
                                         final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) {
                    setColor(0.7f, 0.7f, 0.7f);
                    return true;
                }
                if (pSceneTouchEvent.isActionUp()) {
                    setColor(1, 1, 1);
                    musicControl(MusicOption.PAUSE);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX,
                        pTouchAreaLocalY);
            }
        };

        final Sprite music_stop = new Sprite(Config.getRES_WIDTH() - 50 * 3 + 35,
                47, 40, 40, ResourceManager.getInstance().getTexture(
                "music_stop")) {

            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent,
                                         final float pTouchAreaLocalX,
                                         final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) {
                    setColor(0.7f, 0.7f, 0.7f);
                    doStop = true;
                    return true;
                }
                if (pSceneTouchEvent.isActionUp()) {
                    setColor(1, 1, 1);
                    musicControl(MusicOption.STOP);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX,
                        pTouchAreaLocalY);
            }
        };

        final Sprite music_next = new Sprite(Config.getRES_WIDTH() - 50 * 2 + 35,
                47, 40, 40, ResourceManager.getInstance().getTexture(
                "music_next")) {

            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent,
                                         final float pTouchAreaLocalX,
                                         final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) {
                    setColor(0.7f, 0.7f, 0.7f);
                    doChange = true;
                    return true;
                }
                if (pSceneTouchEvent.isActionUp()) {
                    setColor(1, 1, 1);
                    if (lastHit == 0) {
                        lastHit = System.currentTimeMillis();
                    } else {
                        if (System.currentTimeMillis() - lastHit <= 1000 && !isOnExitAnim) {
                            return true;
                        }
                    }
                    lastHit = System.currentTimeMillis();
                    musicControl(MusicOption.NEXT);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX,
                        pTouchAreaLocalY);
            }
        };

        musicInfoText = new ChangeableText(0, 0, ResourceManager.getInstance().getFont("font"), "", HorizontalAlign.RIGHT, 35);

        final TextureRegion nptex = ResourceManager.getInstance().getTexture("music_np");
        music_nowplay = new Sprite(Utils.toRes(Config.getRES_WIDTH() - 500), 0, (float) (40 * nptex.getWidth()) / nptex.getHeight(), 40, nptex);

        for (int i = 0; i < 120; i++) {
            final float pX = (float) Config.getRES_WIDTH() / 2;
            final float pY = (float) Config.getRES_HEIGHT() / 2;

            spectrum[i] = new Rectangle(pX, pY, 260, 10);
            spectrum[i].setRotationCenter(0, 5);
            spectrum[i].setScaleCenter(0, 5);
            spectrum[i].setRotation(-220 + i * 3f);
            spectrum[i].setAlpha(0.0f);

            scene.attachChild(spectrum[i]);
        }

        TextureRegion starRegion = ResourceManager.getInstance().getTexture("star");

        {
            particleSystem[0] = new ParticleSystem(new PointParticleEmitter(-40, (float) (Config.getRES_HEIGHT() * 3) / 4), 32, 48, 128, starRegion);
            particleSystem[0].setBlendFunction(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);

            particleSystem[0].addParticleInitializer(new VelocityInitializer(150, 430, -480, -520));
            particleSystem[0].addParticleInitializer(new AccelerationInitializer(10, 30));
            particleSystem[0].addParticleInitializer(new RotationInitializer(0.0f, 360.0f));

            particleSystem[0].addParticleModifier(new ScaleModifier(0.5f, 2.0f, 0.0f, 1.0f));
            particleSystem[0].addParticleModifier(new AlphaModifier(1.0f, 0.0f, 0.0f, 1.0f));
            particleSystem[0].addParticleModifier(new ExpireModifier(1.0f));

            particleSystem[0].setParticlesSpawnEnabled(false);

            scene.attachChild(particleSystem[0]);
        }

        {
            particleSystem[1] = new ParticleSystem(new PointParticleEmitter(Config.getRES_WIDTH(), (float) (Config.getRES_HEIGHT() * 3) / 4), 32, 48, 128, starRegion);
            particleSystem[1].setBlendFunction(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);

            particleSystem[1].addParticleInitializer(new VelocityInitializer(-150, -430, -480, -520));
            particleSystem[1].addParticleInitializer(new AccelerationInitializer(-10, 30));
            particleSystem[1].addParticleInitializer(new RotationInitializer(0.0f, 360.0f));

            particleSystem[1].addParticleModifier(new ScaleModifier(0.5f, 2.0f, 0.0f, 1.0f));
            particleSystem[1].addParticleModifier(new AlphaModifier(1.0f, 0.0f, 0.0f, 1.0f));
            particleSystem[1].addParticleModifier(new ExpireModifier(1.0f));

            particleSystem[1].setParticlesSpawnEnabled(false);

            scene.attachChild(particle