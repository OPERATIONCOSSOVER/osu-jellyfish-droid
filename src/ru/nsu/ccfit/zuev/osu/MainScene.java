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
import com.osudroid.ui.SeasonalThemeManager;
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
import org.anddev.andengine.entity.particle.initializer.ColorInitializer;
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

    private static final float BACKGROUND_FADE_DURATION = 1.5f;

    /**
     * The osu! cookie is drawn at its full texture size, the way it has always been.
     *
     * This is kept as a named constant rather than inlined because the beat pulse and the exit
     * animation both set absolute scales. Expressing them against this value keeps them in step
     * with each other, so the logo can be resized in one place without the pulse snapping it back.
     */
    private static final float LOGO_SCALE = 1.0f;

    /** Peak of the on-beat pulse, expressed against {@link #LOGO_SCALE}. */
    private static final float LOGO_BEAT_SCALE = LOGO_SCALE * 1.07f;

    /**
     * Resting length of each spectrum spike, sized so the spikes clear the edge of the cookie.
     * They radiate from the centre of the logo, so anything much shorter than its radius simply
     * disappears behind it.
     */
    private static final float SPECTRUM_BASE_LENGTH = 250f;

    /** Thickness of a single spectrum spike. */
    private static final float SPECTRUM_THICKNESS = 8f;

    /** Compact "now playing" transport bar, sized after the stable main menu's. */
    private static final float MUSIC_BUTTON_SIZE = 34f;
    private static final float MUSIC_BUTTON_SPACING = 42f;
    private static final float MUSIC_BUTTON_Y = 44f;
    private static final float MUSIC_BUTTON_INSET = 30f;

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

    private float seasonalSlideTime = 0;

    private MainMenu menu;

    /** X of the transport button sitting [slot] places in from the right edge. */
    private static float musicButtonX(int slot) {
        return Config.getRES_WIDTH() - MUSIC_BUTTON_SPACING * slot + MUSIC_BUTTON_INSET;
    }

    public void load(Context context) {
        this.context = context;
        Debug.i("Load: mainMenuLoaded()");
        VibratorManager.INSTANCE.init(context);
        scene = new UIScene();

        final TextureRegion tex = getMenuBackgroundTexture();

        if (tex != null) {
            float height = tex.getHeight();
            height *= Config.getRES_WIDTH() / (float) tex.getWidth();
            final Sprite menuBg = new Sprite(0, (Config.getRES_HEIGHT() - height) / 2, Config.getRES_WIDTH(), height, tex);
            scene.setBackground(new SpriteBackground(menuBg));
        } else {
            scene.setBackground(new ColorBackground(70 / 255f, 129 / 255f, 252 / 255f));
        }
        lastBackground = new Sprite(0, 0, Config.getRES_WIDTH(), Config.getRES_HEIGHT(), ResourceManager.getInstance().getTexture("emptyavatar"));

        addSnowfallWithPeriod(scene, context);
        addFireworksWithPeriod(scene, context);

        final TextureRegion logotex = ResourceManager.getInstance().getTexture("logo");
        logo = new Sprite((float) Config.getRES_WIDTH() / 2 - (float) logotex.getWidth() / 2, (float) Config.getRES_HEIGHT() / 2 - (float) logotex.getHeight() / 2, logotex) {
            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent, final float pTouchAreaLocalX, final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) {
                    if (hitsound != null) { hitsound.play(); }
                    return true;
                }
                if (pSceneTouchEvent.isActionUp()) {
                    if (doMenuShow && isMenuShowed) { showPassTime = 20000; }
                    if (!doMenuShow && !isMenuShowed && logo.getX() == (Config.getRES_WIDTH() - logo.getWidth()) / 2) {
                        doMenuShow = true;
                        showPassTime = 0;
                    }
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX, pTouchAreaLocalY);
            }
        };

        // Scaling happens about the sprite's centre, so the cookie stays put and the existing
        // centring maths below stays correct.
        logo.setScale(LOGO_SCALE);

        logoOverlay = new Sprite((float) Config.getRES_WIDTH() / 2 - (float) logotex.getWidth() / 2, (float) Config.getRES_HEIGHT() / 2 - (float) logotex.getHeight() / 2, logotex);
        logoOverlay.setScale(LOGO_BEAT_SCALE);
        logoOverlay.setAlpha(0.2f);

        menu = new MainMenu(this);

        UIBox box = new UIBox() {
            {
                Text versionText = new Text(10f, 2f, ResourceManager.getInstance().getFont("smallFont"), "osu!jellyfish " + BuildConfig.VERSION_NAME);
                attachChild(versionText);
                setSize(versionText.getWidth() + 20f, versionText.getHeight() + 4f);
                setPosition(10f, Config.getRES_HEIGHT() - getHeight() - 10f);
                setColor(0f, 0f, 0f, 0.5f);
                setCornerRadius(12f);
            }
            public boolean onAreaTouched(TouchEvent event, float localX, float localY) {
                if (event.isActionUp()) {
                    new HorizontalMessageDialog()
                        .setTitle("About")
                        .setMessage("<h1>osu!jellyfish</h1>\n<h5>Version " + BuildConfig.VERSION_NAME + "</h5>\n<p>osu!jellyfish, based on osu!droid<br>osu! is \u00a9 peppy 2007-2026</p>\n<br>\n<a href=\"https://osu.ppy.sh\">Visit official osu! website \u2197</a>\n<br>\n<br>\n<a href=\"https://osudroid.moe\">Visit official osu!droid website \u2197</a>\n<br>\n<br>\n<a href=\"https://discord.gg/nyD92cE\">Join the official Discord server \u2197</a>\n", true)
                        .addButton("Changelog", dialog -> { dialog.dismiss(); try { var intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://osudroid.moe/changelog/latest")); context.startActivity(intent); } catch (Exception e) { android.util.Log.e("MainScene", "Failed to load changelog", e); } return null; })
                        .addButton("Close", dialog -> { dialog.dismiss(); return null; })
                        .show();
                }
                return true;
            }
        };
        scene.attachChild(box);

        // Bottom-left credit, stacked above the version badge the way stable stacks its footer.
        final Text footerLink = new Text(0, 0, ResourceManager.getInstance().getFont("smallFont"), "osu.ppy.sh");
        footerLink.setColor(1f, 1f, 1f, 0.5f);
        footerLink.setPosition(12f, Config.getRES_HEIGHT() - box.getHeight() - footerLink.getHeight() - 14f);
        scene.attachChild(footerLink);

        final Text footerCredit = new Text(0, 0, ResourceManager.getInstance().getFont("smallFont"), "ppy powered 2007-2026");
        footerCredit.setColor(1f, 1f, 1f, 0.5f);
        footerCredit.setPosition(12f, footerLink.getY() - footerCredit.getHeight() - 2f);
        scene.attachChild(footerCredit);

        final Sprite music_prev = new Sprite(musicButtonX(6), MUSIC_BUTTON_Y, MUSIC_BUTTON_SIZE, MUSIC_BUTTON_SIZE, ResourceManager.getInstance().getTexture("music_prev")) {
            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent, final float pTouchAreaLocalX, final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) { setColor(0.7f, 0.7f, 0.7f); doChange = true; return true; }
                if (pSceneTouchEvent.isActionUp()) {
                    setColor(1, 1, 1);
                    if (lastHit == 0) { lastHit = System.currentTimeMillis(); } else { if (System.currentTimeMillis() - lastHit <= 1000 && !isOnExitAnim) { return true; } }
                    lastHit = System.currentTimeMillis();
                    musicControl(MusicOption.PREV);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX, pTouchAreaLocalY);
            }
        };

        final Sprite music_play = new Sprite(musicButtonX(5), MUSIC_BUTTON_Y, MUSIC_BUTTON_SIZE, MUSIC_BUTTON_SIZE, ResourceManager.getInstance().getTexture("music_play")) {
            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent, final float pTouchAreaLocalX, final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) { setColor(0.7f, 0.7f, 0.7f); return true; }
                if (pSceneTouchEvent.isActionUp()) { setColor(1, 1, 1); musicControl(MusicOption.PLAY); return true; }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX, pTouchAreaLocalY);
            }
        };

        final Sprite music_pause = new Sprite(musicButtonX(4), MUSIC_BUTTON_Y, MUSIC_BUTTON_SIZE, MUSIC_BUTTON_SIZE, ResourceManager.getInstance().getTexture("music_pause")) {
            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent, final float pTouchAreaLocalX, final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) { setColor(0.7f, 0.7f, 0.7f); return true; }
                if (pSceneTouchEvent.isActionUp()) { setColor(1, 1, 1); musicControl(MusicOption.PAUSE); return true; }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX, pTouchAreaLocalY);
            }
        };

        final Sprite music_stop = new Sprite(musicButtonX(3), MUSIC_BUTTON_Y, MUSIC_BUTTON_SIZE, MUSIC_BUTTON_SIZE, ResourceManager.getInstance().getTexture("music_stop")) {
            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent, final float pTouchAreaLocalX, final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) { setColor(0.7f, 0.7f, 0.7f); doStop = true; return true; }
                if (pSceneTouchEvent.isActionUp()) { setColor(1, 1, 1); musicControl(MusicOption.STOP); return true; }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX, pTouchAreaLocalY);
            }
        };

        final Sprite music_next = new Sprite(musicButtonX(2), MUSIC_BUTTON_Y, MUSIC_BUTTON_SIZE, MUSIC_BUTTON_SIZE, ResourceManager.getInstance().getTexture("music_next")) {
            @Override
            public boolean onAreaTouched(final TouchEvent pSceneTouchEvent, final float pTouchAreaLocalX, final float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) { setColor(0.7f, 0.7f, 0.7f); doChange = true; return true; }
                if (pSceneTouchEvent.isActionUp()) {
                    setColor(1, 1, 1);
                    if (lastHit == 0) { lastHit = System.currentTimeMillis(); } else { if (System.currentTimeMillis() - lastHit <= 1000 && !isOnExitAnim) { return true; } }
                    lastHit = System.currentTimeMillis();
                    musicControl(MusicOption.NEXT);
                    return true;
                }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX, pTouchAreaLocalY);
            }
        };

        musicInfoText = new ChangeableText(0, 0, ResourceManager.getInstance().getFont("font"), "", HorizontalAlign.RIGHT, 35);
        final TextureRegion nptex = ResourceManager.getInstance().getTexture("music_np");
        music_nowplay = new Sprite(Utils.toRes(Config.getRES_WIDTH() - 500), 0, (float) (34 * nptex.getWidth()) / nptex.getHeight(), 34, nptex);

        for (int i = 0; i < 120; i++) {
            final float pX = (float) Config.getRES_WIDTH() / 2;
            final float pY = (float) Config.getRES_HEIGHT() / 2;
            spectrum[i] = new Rectangle(pX, pY, SPECTRUM_BASE_LENGTH + 10f, SPECTRUM_THICKNESS);
            spectrum[i].setRotationCenter(0, SPECTRUM_THICKNESS / 2f);
            spectrum[i].setScaleCenter(0, SPECTRUM_THICKNESS / 2f);
            spectrum[i].setRotation(-220 + i * 3f);
            spectrum[i].setAlpha(0.0f);
            scene.attachChild(spectrum[i]);
        }

        TextureRegion starRegion = ResourceManager.getInstance().getTexture("star");

        // Resolved once so both corner bursts spawn stars in the same accent. The spectrum is
        // handled separately below because it can be recoloured without respawning anything.
        final float[] seasonalAccent = SeasonalThemeManager.getAccentColor();

        {
            particleSystem[0] = new ParticleSystem(new PointParticleEmitter(-40, (float) (Config.getRES_HEIGHT() * 3) / 4), 32, 48, 128, starRegion);
            particleSystem[0].setBlendFunction(GL10.GL_SRC_ALPHA, GL10.GL_ONE_MINUS_SRC_ALPHA);
            particleSystem[0].addParticleInitializer(new VelocityInitializer(150, 430, -480, -520));
            particleSystem[0].addParticleInitializer(new AccelerationInitializer(10, 30));
            particleSystem[0].addParticleInitializer(new RotationInitializer(0.0f, 360.0f));
            if (seasonalAccent != null) {
                particleSystem[0].addParticleInitializer(new ColorInitializer(seasonalAccent[0], seasonalAccent[1], seasonalAccent[2]));
            }
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
            if (seasonalAccent != null) {
                particleSystem[1].addParticleInitializer(new ColorInitializer(seasonalAccent[0], seasonalAccent[1], seasonalAccent[2]));
            }
            particleSystem[1].addParticleModifier(new ScaleModifier(0.5f, 2.0f, 0.0f, 1.0f));
            particleSystem[1].addParticleModifier(new AlphaModifier(1.0f, 0.0f, 0.0f, 1.0f));
            particleSystem[1].addParticleModifier(new ExpireModifier(1.0f));
            particleSystem[1].setParticlesSpawnEnabled(false);
            scene.attachChild(particleSystem[1]);
        }

        TextureRegion beatmapDownloaderTex = ResourceManager.getInstance().getTexture("beatmap_downloader");
        Sprite beatmapDownloader = new Sprite(Config.getRES_WIDTH() - beatmapDownloaderTex.getWidth(), (Config.getRES_HEIGHT() - beatmapDownloaderTex.getHeight()) / 2f, beatmapDownloaderTex) {
            public boolean onAreaTouched(TouchEvent pSceneTouchEvent, float pTouchAreaLocalX, float pTouchAreaLocalY) {
                if (pSceneTouchEvent.isActionDown()) { setColor(0.7f, 0.7f, 0.7f); doStop = true; return true; }
                if (pSceneTouchEvent.isActionUp()) { setColor(1, 1, 1); new BeatmapListing().show(); return true; }
                return super.onAreaTouched(pSceneTouchEvent, pTouchAreaLocalX, pTouchAreaLocalY);
            }
        };

        menu.getFirst().setAlpha(0f);
        menu.getSecond().setAlpha(0f);
        menu.getThird().setAlpha(0f);

        logo.setPosition((Config.getRES_WIDTH() - logo.getWidth()) / 2, (Config.getRES_HEIGHT() - logo.getHeight()) / 2);
        logoOverlay.setPosition((Config.getRES_WIDTH() - logo.getWidth()) / 2, (Config.getRES_HEIGHT() - logo.getHeight()) / 2);

        menu.getSecond().setScale(Config.getRES_WIDTH() / 1024f);
        menu.getFirst().setScale(Config.getRES_WIDTH() / 1024f);
        menu.getThird().setScale(Config.getRES_WIDTH() / 1024f);

        menu.getSecond().setPosition(logo.getX() + logo.getWidth() - Config.getRES_WIDTH() / 2.5f, (Config.getRES_HEIGHT() - menu.getSecond().getHeight()) / 2);
        menu.getFirst().setPosition(logo.getX() + logo.getWidth() - Config.getRES_WIDTH() / 2.5f, menu.getSecond().getY() - menu.getFirst().getHeight() - 40 * Config.getRES_WIDTH() / 1024f);
        menu.getThird().setPosition(logo.getX() + logo.getWidth() - Config.getRES_WIDTH() / 2.5f, menu.getSecond().getY() + menu.getThird().getHeight() + 40 * Config.getRES_WIDTH() / 1024f);

        menuBarX = menu.getFirst().getX();

        scene.attachChild(lastBackground, 0);
        scene.attachChild(logo);
        scene.attachChild(logoOverlay);
        scene.attachChild(music_nowplay);
        scene.attachChild(musicInfoText);
        scene.attachChild(music_prev);
        scene.attachChild(music_play);
        scene.attachChild(music_pause);
        scene.attachChild(music_stop);
        scene.attachChild(music_next);
        scene.attachChild(beatmapDownloader);

        scene.registerTouchArea(logo);
        scene.registerTouchArea(box);
        scene.registerTouchArea(beatmapDownloader);
        scene.registerTouchArea(music_prev);
        scene.registerTouchArea(music_play);
        scene.registerTouchArea(music_pause);
        scene.registerTouchArea(music_stop);
        scene.registerTouchArea(music_next);

        if (BuildConfig.DEBUG) {
            ResourceManager.getInstance().loadHighQualityAsset("dev-build-overlay", "dev-build-overlay.png");
            UISprite debugOverlay = new UISprite(ResourceManager.getInstance().getTexture("dev-build-overlay"));
            debugOverlay.setPosition(Config.getRES_WIDTH() / 2f, Config.getRES_HEIGHT());
            debugOverlay.setOrigin(Anchor.BottomCenter);
            scene.attachChild(debugOverlay);
            Text debugText = new Text(0, 0, ResourceManager.getInstance().getFont("smallFont"), "DEVELOPMENT BUILD");
            debugText.setColor(1f, 237f / 255f, 0f);
            debugText.setPosition((Config.getRES_WIDTH() - debugText.getWidth()) / 2f, Config.getRES_HEIGHT() - debugOverlay.getHeight() - 1f - debugText.getHeight());
            Text debugTextShadow = new Text(0, 0, ResourceManager.getInstance().getFont("smallFont"), "DEVELOPMENT BUILD");
            debugTextShadow.setColor(0f, 0f, 0f, 0.5f);
            debugTextShadow.setPosition((Config.getRES_WIDTH() - debugText.getWidth()) / 2f + 2f, Config.getRES_HEIGHT() - debugOverlay.getHeight() - 1f - debugText.getHeight() + 2f);
            scene.attachChild(debugTextShadow);
            scene.attachChild(debugText);
        }

        progressBar = new LinearSongProgress(scene, 0, 0, new PointF(Utils.toRes(Config.getRES_WIDTH() - 320), Utils.toRes(100)));
        progressBar.setProgressRectColor(new Color4(0.9f, 0.9f, 0.9f));
        progressBar.setProgressRectAlpha(0.8f);

        applySeasonalAccent();

        createOnlinePanel(scene);
        scene.registerUpdateHandler(this);

        hitsound = ResourceManager.getInstance().loadSound("menuhit", "sfx/menuhit.ogg", false);
    }

    /**
     * Recolours the spectrum radiating out of the osu! cookie to the accent of whatever season is
     * running, falling back to white when nothing is in season or the setting is off.
     *
     * Only the colour is touched, never the alpha, which the spectrum animates itself every frame.
     * This runs again whenever settings are reapplied so the toggle takes effect straight away.
     */
    private void applySeasonalAccent() {
        final float[] accent = SeasonalThemeManager.getAccentColor();

        for (final Rectangle bar : spectrum) {
            if (bar == null) { continue; }

            if (accent != null) {
                bar.setColor(accent[0], accent[1], accent[2]);
            } else {
                bar.setColor(1f, 1f, 1f);
            }
        }
    }

    private TextureRegion getMenuBackgroundTexture() {
        final TextureRegion seasonal = SeasonalBackgroundManager.load();
        if (seasonal != null) { return seasonal; }
        return ResourceManager.getInstance().getTexture("menu-background");
    }

    private void applyBackgroundTexture(TextureRegion tex, float fadeDuration) {
        if (tex == null) { return; }
        float height = tex.getHeight();
        height *= Config.getRES_WIDTH() / (float) tex.getWidth();
        final Sprite incoming = new Sprite(0, (Config.getRES_HEIGHT() - height) / 2, Config.getRES_WIDTH(), height, tex);
        background = incoming;
        lastBackground.registerEntityModifier(new org.anddev.andengine.entity.modifier.AlphaModifier(fadeDuration, 1, 0, new IEntityModifier.IEntityModifierListener() {
            @Override
            public void onModifierStarted(IModifier<IEntity> pModifier, IEntity pItem) { scene.attachChild(incoming, 0); }
            @Override
            public void onModifierFinished(IModifier<IEntity> pModifier, final IEntity pItem) { GlobalManager.getInstance().getMainActivity().runOnUpdateThread(pItem::detachSelf); }
        }));
        lastBackground = incoming;
    }

    private void updateSeasonalSlideshow(final float pSecondsElapsed) {
        if (!SeasonalBackgroundManager.isSlideshowEnabled()) { seasonalSlideTime = 0; return; }
        seasonalSlideTime += pSecondsElapsed;
        if (seasonalSlideTime < SeasonalBackgroundManager.getIntervalSeconds()) { return; }
        seasonalSlideTime = 0;
        try { applyBackgroundTexture(SeasonalBackgroundManager.next(), BACKGROUND_FADE_DURATION); } catch (Exception e) { Debug.e("Failed to advance the seasonal background: " + e); }
    }

    public void loadBannerSprite() {
        if (!Config.isStayOnline()) { return; }
        BannerSprite sprite = BannerManager.loadBannerSprite();
        if (sprite != null) {
            sprite.setPosition(Config.getRES_WIDTH(), Config.getRES_HEIGHT());
            sprite.setOrigin(Anchor.BottomRight);
            scene.attachChild(sprite);
        }
    }

    private void createOnlinePanel(UIScene scene) {
        Config.loadOnlineConfig(context);
        OnlineManager.getInstance().init();
        if (OnlineManager.getInstance().isStayOnline()) {
            Debug.i("Stay online, creating panel");
            OnlineScoring.getInstance().createPanel();
            final OnlinePanel panel = OnlineScoring.getInstance().getPanel();
            panel.setPosition(5, 5);
            scene.attachChild(panel);
            scene.registerTouchArea(panel.rect);
        }
        OnlineScoring.getInstance().login();
    }

    public void reloadOnlinePanel() {
        Execution.updateThread(() -> {
            scene.detachChild(OnlineScoring.getInstance().getPanel());
            createOnlinePanel(scene);
        });
    }

    public void musicControl(MusicOption option) {
        if (GlobalManager.getInstance().getSongService() == null || beatmapInfo == null) { return; }
        switch (option) {
            case PREV: {
                if (GlobalManager.getInstance().getSongService().getStatus() == Status.PLAYING || GlobalManager.getInstance().getSongService().getStatus() == Status.PAUSED) { GlobalManager.getInstance().getSongService().stop(); }
                currentTimingPoint = null;
                LibraryManager.selectPreviousBeatmapSet();
                loadBeatmapInfo();
                loadTimingPoints(true);
                doChange = false;
                doStop = false;
            }
            break;
            case PLAY: {
                // Only while the intro still owns the menu. Once song select has been entered the
                // player controls the library track, so the theme must not hijack playback.
                if (ThemeSongManager.isIntroActive()) {
                    ThemeSongManager.play();
                    break;
                }
                if (GlobalManager.getInstance().getSongService().getStatus() == Status.PAUSED || GlobalManager.getInstance().getSongService().getStatus() == Status.STOPPED) {
                    if (GlobalManager.getInstance().getSongService().getStatus() == Status.STOPPED) {
                        loadTimingPoints(false);
                        GlobalManager.getInstance().getSongService().preLoad(beatmapInfo.getAudioPath());
                        if (currentTimingPoint != null) { bpmLength = currentTimingPoint.msPerBeat; beatPassTime = 0; }
                    }
                    if (GlobalManager.getInstance().getSongService().getStatus() == Status.PAUSED && currentTimingPoint != null) {
                        bpmLength = currentTimingPoint.msPerBeat;
                        int position = GlobalManager.getInstance().getSongService().getPosition();
                        beatPassTime = (position - currentTimingPoint.time) % bpmLength;
                    }
                    GlobalManager.getInstance().getSongService().play();
                    doStop = false;
                }
            }
            break;
            case PAUSE: {
                if (GlobalManager.getInstance().getSongService().getStatus() == Status.PLAYING) { GlobalManager.getInstance().getSongService().pause(); bpmLength = 1000; beatPassTime = 0; }
            }
            break;
            case STOP: {
                if (GlobalManager.getInstance().getSongService().getStatus() == Status.PLAYING || GlobalManager.getInstance().getSongService().getStatus() == Status.PAUSED) { GlobalManager.getInstance().getSongService().stop(); bpmLength = 1000; beatPassTime = 0; }
            }
            break;
            case NEXT: {
                if (GlobalManager.getInstance().getSongService().getStatus() == Status.PLAYING || GlobalManager.getInstance().getSongService().getStatus() == Status.PAUSED) { GlobalManager.getInstance().getSongService().stop(); }
                LibraryManager.selectNextBeatmapSet();
                currentTimingPoint = null;
                loadBeatmapInfo();
                loadTimingPoints(true);
                doChange = false;
                doStop = false;
            }
            break;
            case SYNC: {
                if (GlobalManager.getInstance().getSongService().getStatus() == Status.PLAYING && currentTimingPoint != null) {
                    int position = GlobalManager.getInstance().getSongService().getPosition();
                    beatPassTime = (position - currentTimingPoint.time) % bpmLength;
                }
            }
        }
    }

    @Override
    public void onUpdate(final float pSecondsElapsed) {
        beatPassTime += pSecondsElapsed * 1000;
        if (isOnExitAnim) {
            for (Rectangle specRectangle : spectrum) { specRectangle.setWidth(0); specRectangle.setAlpha(0); }
            return;
        }
        updateSeasonalSlideshow(pSecondsElapsed);
        if (GlobalManager.getInstance().getSongService() == null || !musicStarted || GlobalManager.getInstance().getSongService().getStatus() == Status.STOPPED) { bpmLength = 1000; }
        if (doMenuShow && !isMenuShowed) {
            logo.registerEntityModifier(new MoveXModifier(0.3f, (float) Config.getRES_WIDTH() / 2 - logo.getWidth() / 2, (float) Config.getRES_WIDTH() / 3 - logo.getWidth() / 2, EaseExponentialOut.getInstance()));
            logoOverlay.registerEntityModifier(new MoveXModifier(0.3f, (float) Config.getRES_WIDTH() / 2 - logo.getWidth() / 2, (float) Config.getRES_WIDTH() / 3 - logo.getWidth() / 2, EaseExponentialOut.getInstance()));
            for (Rectangle rectangle : spectrum) { rectangle.registerEntityModifier(new MoveXModifier(0.3f, (float) Config.getRES_WIDTH() / 2, (float) Config.getRES_WIDTH() / 3, EaseExponentialOut.getInstance())); }
            menu.attachButtons();
            menu.showFirstMenu();
            for (var button : menu.getButtons()) {
                button.clearEntityModifiers();
                button.setX(menuBarX - 100);
                button.setAlpha(0f);
                button.beginModifierSequence(sequence -> sequence.moveToX(menuBarX, 0.5f, Easing.OutElastic).fadeTo(0.9f, 0.5f, Easing.OutCubic));
            }
            isMenuShowed = true;
        }
        if (doMenuShow) {
            if (showPassTime > 10000f) {
                menu.showFirstMenu();
                for (var button : menu.getButtons()) {
                    scene.unregisterTouchArea(button);
                    button.clearEntityModifiers();
                    button.setX(menuBarX);
                    button.setAlpha(0.9f);
                    button.beginModifierSequence(sequence -> sequence.moveToX(menuBarX - 50, 1f, Easing.OutExpo).fadeOut(1f, Easing.OutExpo).after(IEntity::detachSelf));
                }
                logo.registerEntityModifier(new MoveXModifier(1f, (float) Config.getRES_WIDTH() / 3 - logo.getWidth() / 2, (float) Config.getRES_WIDTH() / 2 - logo.getWidth() / 2, EaseBounceOut.getInstance()));
                logoOverlay.registerEntityModifier(new MoveXModifier(1f, (float) Config.getRES_WIDTH() / 3 - logo.getWidth() / 2, (float) Config.getRES_WIDTH() / 2 - logo.getWidth() / 2, EaseBounceOut.getInstance()));
                for (Rectangle rectangle : spectrum) { rectangle.registerEntityModifier(new MoveXModifier(1f, (float) Config.getRES_WIDTH() / 3, (float) Config.getRES_WIDTH() / 2, EaseBounceOut.getInstance())); }
                isMenuShowed = false;
                doMenuShow = false;
                showPassTime = 0;
            } else { showPassTime += pSecondsElapsed * 1000f; }
        }
        if (beatPassTime >= bpmLength) {
            beatPassTime %= bpmLength;
            if (logo != null) { logo.registerEntityModifier(new SequenceEntityModifier(new org.anddev.andengine.entity.modifier.ScaleModifier((float) (bpmLength / 1000 * 0.9f), LOGO_SCALE, LOGO_BEAT_SCALE), new org.anddev.andengine.entity.modifier.ScaleModifier((float) (bpmLength / 1000 * 0.07f), LOGO_BEAT_SCALE, LOGO_SCALE))); }
        }
        if (GlobalManager.getInstance().getSongService() != null) {
            if (!musicStarted) {
                if (currentTimingPoint == null) { return; }
                bpmLength = currentTimingPoint.msPerBeat;
                beatPassTime = 0;
                progressBar.setStartTime(0);
                GlobalManager.getInstance().getSongService().play();
                GlobalManager.getInstance().getSongService().setVolume(Config.getBgmVolume());
                musicStarted = true;
            }
            if (GlobalManager.getInstance().getSongService().getStatus() == Status.PLAYING) {
                int position = GlobalManager.getInstance().getSongService().getPosition();
                progressBar.setTime(GlobalManager.getInstance().getSongService().getLength());
                progressBar.setPassedTime(position);
                progressBar.update(pSecondsElapsed * 1000);
                if (timingControlPoints != null && !timingControlPoints.isEmpty() && position > timingControlPoints.peek().time) {
                    while (!timingControlPoints.isEmpty() && position > timingControlPoints.peek().time) { currentTimingPoint = timingControlPoints.pop(); }
                    bpmLength = currentTimingPoint.msPerBeat;
                    beatPassTime = (position - currentTimingPoint.time) % bpmLength;
                }
                if (effectControlPoints != null && !effectControlPoints.isEmpty() && position > effectControlPoints.peek().time) {
                    while (!effectControlPoints.isEmpty() && position > effectControlPoints.peek().time) { currentEffectPoint = effectControlPoints.pop(); }
                    if (!isContinuousKiai && currentEffectPoint.isKiai) {
                        for (ParticleSystem particleSpout : particleSystem) { particleSpout.setParticlesSpawnEnabled(true); }
                        particleBeginTime = position;
                        particleEnabled = true;
                    }
                    isContinuousKiai = currentEffectPoint.isKiai;
                }
                if (particleEnabled && (position - particleBeginTime > 2000)) {
                    for (ParticleSystem particleSpout : particleSystem) { particleSpout.setParticlesSpawnEnabled(false); }
                    particleEnabled = false;
                }
                int windowSize = 240;
                int spectrumWidth = 120;
                float[] fft = GlobalManager.getInstance().getSongService().getSpectrum();
                if (fft == null) return;
                for (int i = 0, leftBound = 0; i < spectrumWidth; i++) {
                    float peak = 0;
                    int rightBound = (int) Math.pow(2., i * 9. / (windowSize - 1));
                    if (rightBound <= leftBound) rightBound = leftBound + 1;
                    if (rightBound > 511) rightBound = 511;
                    for (; leftBound < rightBound; leftBound++) { if (peak < fft[1 + leftBound]) peak = fft[1 + leftBound]; }
                    float initialAlpha = 0.4f;
                    float gradient = 20;
                    float currPeakLevel = peak * 500;
                    if (currPeakLevel > peakLevel[i]) {
                        peakLevel[i] = currPeakLevel;
                        peakDownRate[i] = peakLevel[i] / gradient;
                        peakAlpha[i] = initialAlpha;
                    } else {
                        peakLevel[i] = Math.max(peakLevel[i] - peakDownRate[i], 0f);
                        peakAlpha[i] = Math.max(peakAlpha[i] - initialAlpha / gradient, 0f);
                    }
                    spectrum[i].setWidth(SPECTRUM_BASE_LENGTH + peakLevel[i]);
                    spectrum[i].setAlpha(peakAlpha[i]);
                }
            } else {
                for (Rectangle specRectangle : spectrum) { specRectangle.setWidth(0); specRectangle.setAlpha(0); }
                if (!doChange && !doStop && !ThemeSongManager.isIntroActive() && GlobalManager.getInstance().getSongService() != null && GlobalManager.getInstance().getSongService().getPosition() >= GlobalManager.getInstance().getSongService().getLength()) {
                    musicControl(MusicOption.NEXT);
                }
            }
        }
    }

    @Override
    public void reset() {}

    public void loadBeatmap() {
        LibraryManager.shuffleLibrary();
        loadBeatmapInfo();
        loadTimingPoints(true);
    }

    public void loadBeatmapInfo() {
        // While the intro owns the menu, "now playing" names the track that is actually audible
        // rather than whichever beatmap the shuffled library happens to be sitting on.
        final String introLabel = ThemeSongManager.isIntroActive() ? ThemeSongManager.getIntroDisplayName() : null;

        if (LibraryManager.getSizeOfBeatmaps() != 0) {
            beatmapInfo = LibraryManager.getCurrentBeatmapSet().getBeatmap(0);
        }

        if (beatmapInfo == null && introLabel == null) { return; }

        if (musicInfoText == null) {
            musicInfoText = new ChangeableText(Utils.toRes(Config.getRES_WIDTH() - 500), Utils.toRes(3), ResourceManager.getInstance().getFont("font"), "None...", HorizontalAlign.RIGHT, 35);
        }

        final String label = introLabel != null
            ? introLabel
            : beatmapInfo.getArtistText() + " - " + beatmapInfo.getTitleText();

        musicInfoText.setText(label, true);

        try {
            musicInfoText.setPosition(Utils.toRes(Config.getRES_WIDTH() - 500 + 470 - musicInfoText.getWidth()), musicInfoText.getY());
            music_nowplay.setPosition(Utils.toRes(Config.getRES_WIDTH() - 500 + 470 - musicInfoText.getWidth() - 130), 0);
        } catch (NullPointerException e) {
            musicInfoText.setPosition(Utils.toRes(Config.getRES_WIDTH() - 500 + 470 - 200), 5);
            music_nowplay.setPosition(Utils.toRes(Config.getRES_WIDTH() - 500 + 470 - 200 - 130), 0);
        }
    }

    public void loadTimingPoints(boolean reloadMusic) {
        // Reapplied here so toggling the seasonal setting is picked up as soon as settings close.
        applySeasonalAccent();

        final boolean intro = ThemeSongManager.isIntroActive();

        // With an empty library there is no beatmap to fall back on, but the intro can still play
        // and beat on its own.
        if (beatmapInfo == null) {
            if (intro) {
                if (reloadMusic) { ThemeSongManager.play(); musicStarted = true; }
                applyIntroTiming();
            }
            return;
        }

        for (ParticleSystem particleSpout : particleSystem) { particleSpout.setParticlesSpawnEnabled(false); }
        particleEnabled = false;
        GlobalManager.getInstance().setSelectedBeatmap(beatmapInfo);
        final TextureRegion seasonalTex = SeasonalBackgroundManager.load();
        if (seasonalTex != null || beatmapInfo.getBackgroundFilename() != null) {
            try {
                final TextureRegion tex;
                if (seasonalTex != null) { tex = seasonalTex; }
                else if (Config.isSafeBeatmapBg()) { tex = ResourceManager.getInstance().getTexture("menu-background"); }
                else { tex = ResourceManager.getInstance().loadBackground(beatmapInfo.getBackgroundPath()); }
                applyBackgroundTexture(tex, BACKGROUND_FADE_DURATION);
            } catch (Exception e) { Debug.e(e.toString()); lastBackground.setAlpha(0); }
        } else { lastBackground.setAlpha(0); }
        if (reloadMusic) {
            if (intro) {
                ThemeSongManager.play();
                musicStarted = true;
            } else if (GlobalManager.getInstance().getSongService() != null) {
                GlobalManager.getInstance().getSongService().preLoad(beatmapInfo.getAudioPath());
                musicStarted = false;
            } else {
                Log.w("nullpoint", "GlobalManager.getInstance().getSongService() is null while reload music (MainScene.loadTimeingPoints)");
            }
        }
        Arrays.fill(peakLevel, 0f);
        Arrays.fill(peakDownRate, 1f);
        Arrays.fill(peakAlpha, 0f);

        // The intro is what is playing, so the cookie has to pulse to its timing rather than to a
        // beatmap that is not audible.
        if (intro && ThemeSongManager.hasIntroTiming()) {
            applyIntroTiming();
            return;
        }

        try {
            var beatmap = BeatmapCache.getBeatmap(beatmapInfo, false);
            var timingControlPoints = new LinkedList<>(beatmap.getControlPoints().timing.controlPoints);
            var effectControlPoints = new LinkedList<>(beatmap.getControlPoints().effect.controlPoints);
            int position = GlobalManager.getInstance().getSongService() != null ? GlobalManager.getInstance().getSongService().getPosition() : 0;
            TimingControlPoint currentTimingPoint = null;
            EffectControlPoint currentEffectPoint = null;
            while (!timingControlPoints.isEmpty() && position > timingControlPoints.peek().time) { currentTimingPoint = timingControlPoints.pop(); }
            while (!effectControlPoints.isEmpty() && position > effectControlPoints.peek().time) { currentEffectPoint = effectControlPoints.pop(); }
            if (currentTimingPoint == null) { currentTimingPoint = beatmap.getControlPoints().timing.defaultControlPoint; }
            if (currentEffectPoint == null) { currentEffectPoint = beatmap.getControlPoints().effect.defaultControlPoint; }
            this.timingControlPoints = timingControlPoints;
            this.effectControlPoints = effectControlPoints;
            this.currentTimingPoint = currentTimingPoint;
            this.currentEffectPoint = currentEffectPoint;
            bpmLength = currentTimingPoint.msPerBeat;
            beatPassTime = (position - currentTimingPoint.time) % bpmLength;
        } catch (IOException | IllegalArgumentException e) { Debug.e("Failed to load beatmap for timing points: " + e); }
    }

    /**
     * Points the menu's beat at the intro's own timing points, parsed from the .osu inside the
     * theme .osz. Mirrors the beatmap path below: the queues are wound forward to wherever the
     * track already is, and whatever is left is consumed frame by frame in {@link #onUpdate}.
     */
    private void applyIntroTiming() {
        if (!ThemeSongManager.hasIntroTiming()) { return; }

        final LinkedList<TimingControlPoint> introTiming = ThemeSongManager.getIntroTimingPoints();
        final LinkedList<EffectControlPoint> introEffects = ThemeSongManager.getIntroEffectPoints();

        int position = GlobalManager.getInstance().getSongService() != null ? GlobalManager.getInstance().getSongService().getPosition() : 0;

        TimingControlPoint timingPoint = null;
        EffectControlPoint effectPoint = null;

        while (!introTiming.isEmpty() && position > introTiming.peek().time) { timingPoint = introTiming.pop(); }
        while (!introEffects.isEmpty() && position > introEffects.peek().time) { effectPoint = introEffects.pop(); }

        // Before the first timing point the track has not started yet, so borrow the upcoming one
        // rather than leaving the cookie on the placeholder 1000 ms beat.
        if (timingPoint == null && !introTiming.isEmpty()) { timingPoint = introTiming.peek(); }

        this.timingControlPoints = introTiming;
        this.effectControlPoints = introEffects;
        this.currentTimingPoint = timingPoint;
        this.currentEffectPoint = effectPoint;

        if (timingPoint != null) {
            bpmLength = timingPoint.msPerBeat;
            beatPassTime = (position - timingPoint.time) % bpmLength;
        }
    }

    public void showExitDialog() {
        if (isOnExitAnim) { return; }
        var exitDialog = new UIConfirmDialog();
        exitDialog.setTitle("Exit");
        exitDialog.setText(context.getString(com.osudroid.resources.R.string.dialog_exit_message));
        exitDialog.setOnConfirm(() -> { exit(); return null; });
        exitDialog.show();
    }

    public void exit() {
        if (isOnExitAnim) { return; }
        isOnExitAnim = true;
        Execution.updateThread(menu::detachButtons);
        BassSoundProvider exitsound = ResourceManager.getInstance().getSound("seeya");
        if (exitsound != null) { exitsound.play(); }
        Rectangle bg = new Rectangle(0, 0, Config.getRES_WIDTH(), Config.getRES_HEIGHT());
        bg.setColor(0, 0, 0, 1.0f);
        bg.registerEntityModifier(new org.anddev.andengine.entity.modifier.AlphaModifier(3.0f, 0, 1));
        scene.attachChild(bg);
        logo.registerEntityModifier(new ParallelEntityModifier(new RotationModifier(3.0f, 0, -15), new org.anddev.andengine.entity.modifier.ScaleModifier(3.0f, LOGO_SCALE, LOGO_SCALE * 0.8f)));
        logoOverlay.registerEntityModifier(new ParallelEntityModifier(new RotationModifier(3.0f, 0, -15), new org.anddev.andengine.entity.modifier.ScaleModifier(3.0f, LOGO_BEAT_SCALE, LOGO_BEAT_SCALE * 0.8f)));
        if (GlobalManager.getInstance().getSongService() != null) { GlobalManager.getInstance().getSongService().stop(); }
        ScheduledExecutorService taskPool = Executors.newScheduledThreadPool(1);
        taskPool.schedule(new TimerTask() { @Override public void run() { GlobalManager.getInstance().getMainActivity().finish(); } }, 3000, TimeUnit.MILLISECONDS);
    }

    public UIScene getScene() { return scene; }
    public BeatmapInfo getBeatmapInfo() { return beatmapInfo; }

    public void setBeatmap(BeatmapInfo beatmapInfo) {
        LibraryManager.findBeatmapSetIndex(beatmapInfo);
        this.beatmapInfo = beatmapInfo;
        loadBeatmapInfo();
        loadTimingPoints(false);
        musicControl(MusicOption.SYNC);
    }

    public void watchReplay(String replayFile) {
        Replay replay = new Replay();
        if (!replay.load(replayFile, false) || replay.replayVersion < 3) { return; }
        BeatmapInfo beatmap = LibraryManager.findBeatmapByMD5(replay.getMd5());
        if (beatmap == null) { return; }
        GlobalManager.getInstance().getMainScene().setBeatmap(beatmap);
        StatisticV2 stat = replay.getStat();
        stat.migrateLegacyMods(beatmap.getBeatmapDifficulty());
        stat.calculateModScoreMultiplier(beatmap.getBeatmapDifficulty());
        GlobalManager.getInstance().getSongMenu().select();
        ResourceManager.getInstance().loadBackground(beatmap.getBackgroundPath());
        GlobalManager.getInstance().getSongService().preLoad(beatmap.getAudioPath());
        GlobalManager.getInstance().getSongService().play();
        ScoringScene scorescene = GlobalManager.getInstance().getScoring();
        scorescene.load(stat, null, GlobalManager.getInstance().getSongService(), replayFile, null, beatmap);
        GlobalManager.getInstance().getEngine().setScene(scorescene.getScene());
    }

    public void show() {
        GlobalManager.getInstance().getSongService().setGaming(false);
        GlobalManager.getInstance().getEngine().setScene(getScene());
        applySeasonalAccent();

        // Coming back from song select hands the menu to whatever the player selected there. The
        // intro is only ever heard on the way into the game, so it is not resumed here.
        if (ThemeSongManager.isIntroActive()) {
            ThemeSongManager.play();
        } else if (GlobalManager.getInstance().getSelectedBeatmap() != null) {
            setBeatmap(GlobalManager.getInstance().getSelectedBeatmap());
        }
    }

    public enum MusicOption {PREV, PLAY, PAUSE, STOP, NEXT, SYNC}
}
