package ru.nsu.ccfit.zuev.osu.online;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.osudroid.data.BeatmapInfo;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;

import okhttp3.RequestBody;
import org.anddev.andengine.util.Debug;

import java.io.File;
import java.util.ArrayList;

import ru.nsu.ccfit.zuev.osu.Config;
import ru.nsu.ccfit.zuev.osu.ResourceManager;
import ru.nsu.ccfit.zuev.osu.helper.FileUtils;
import ru.nsu.ccfit.zuev.osu.*;
import ru.nsu.ccfit.zuev.osu.helper.MD5Calculator;
import ru.nsu.ccfit.zuev.osu.online.PostBuilder.RequestException;
import ru.nsu.ccfit.zuev.osu.scoring.BeatmapLeaderboardScoringMode;

public class OnlineManager {

    /**
     * Master switch for the offline-only build.
     * <p>
     * While this is {@code true} the game never talks to the osu!droid server: logging in, score
     * submission, leaderboards, avatars, profile banners, ranked-status lookups and update checks
     * are all short-circuited before any request is built. Everything that does not need a server
     * (local beatmaps, local scores, replays, skins) keeps working exactly as before.
     */
    public static final boolean OFFLINE_MODE = true;

    /** Message shown wherever a server error message would normally be displayed. */
    public static final String OFFLINE_MESSAGE = "Offline build, online features are disabled";

    public static final String hostname = "osudroid.moe";
    public static final String endpoint = "https://" + hostname + "/api/";
    public static final String updateEndpoint = endpoint + "update.php?lang=";
    public static final String defaultAvatarURL = getAvatarURL(0);
    public static final String profileBannerEndpoint = "https://" + hostname + "/user/banner/";
    private static final String onlineVersion = "61";

    public static final OkHttpClient client = new OkHttpClient();

    private static OnlineManager instance = null;
    private String failMessage = "";

    private boolean stayOnline = !OFFLINE_MODE;
    private String ssid = "";
    private long userId = -1L;

    private String username = "";
    private String password = "";
    private String deviceID = "";
    private long rank = 0;
    private long score = 0;
    private float accuracy = 0;
    private float pp = 0;
    private String avatarURL = "";
    private String profileBannerURL = "";
    private int mapRank;

    public static OnlineManager getInstance() {
        if (instance == null) {
            instance = new OnlineManager();
        }
        return instance;
    }

    public static String getReplayURL(int playID) {
        return switch (Config.getBeatmapLeaderboardScoringMode()) {
            case SCORE -> endpoint + "upload/" + playID + ".odr";
            case PP -> endpoint + "bestpp/" + playID + ".odr";
        };
    }

    public static String getAvatarURL(long userId) {
        return "https://" + hostname + "/user/avatar/" + userId + ".png";
    }

    public static String getProfileBannerURL(long userId) {
        return profileBannerEndpoint + userId + ".png";
    }

    public void init() {
        this.username = Config.getOnlineUsername();
        this.password = Config.getOnlinePassword();
        this.deviceID = Config.getOnlineDeviceID();

        if (OFFLINE_MODE) {
            Debug.i("OnlineManager: offline build, staying offline.");
            this.stayOnline = false;
            this.failMessage = OFFLINE_MESSAGE;
            return;
        }

        this.stayOnline = Config.isStayOnline();
    }

    private ArrayList<String> sendRequest(PostBuilder post, String url) throws OnlineManagerException {
        if (OFFLINE_MODE) {
            Debug.i("OnlineManager: request to " + url + " skipped, offline build.");
            failMessage = OFFLINE_MESSAGE;
            return null;
        }

        ArrayList<String> response;
        try {
            response = post.requestWithAttempts(url, 3);
        } catch (RequestException e) {
            Debug.e(e.getMessage(), e);
            failMessage = "Cannot connect to server";
            throw new OnlineManagerException("Cannot connect to server", e);
        }
        failMessage = "";

        if (response.size() == 0 || response.get(0).length() == 0) {
            failMessage = "Got empty response";
            Debug.i("Received empty response!");
            return null;
        }

        if (!response.get(0).equals("SUCCESS")) {
            Debug.i("sendRequest response code:  " + response.get(0));
            if (response.size() >= 2) {
                failMessage = response.get(1);
            } else
                failMessage = "Unknown server error";
            Debug.i("Received fail: " + failMessage);
            return null;
        }


        return response;
    }

    public boolean logIn() throws OnlineManagerException {
        return logIn(username, password);
    }

    public boolean logIn(String username) throws OnlineManagerException {
        return logIn(username, password);
    }

    public synchronized boolean logIn(String username, String password) throws OnlineManagerException {
        this.username = username;
        this.password = password;

        if (OFFLINE_MODE) {
            Debug.i("OnlineManager: log in skipped, offline build.");
            stayOnline = false;
            failMessage = OFFLINE_MESSAGE;
            return false;
        }

        PostBuilder post = new URLEncodedPostBuilder();
        post.addParam("username", username);
        post.addParam("password", MD5Calculator.getStringMD5(password.trim() + "taikotaiko"));
        post.addParam("version", onlineVersion);

        ArrayList<String> response = sendRequest(post, endpoint + "login.php");

        if (response == null) {
            return false;
        }
        if (response.size() < 2) {
            failMessage = "Invalid server response";
            return false;
        }

        String[] params = response.get(1).split("\\s+");
        if (params.length < 7) {
            failMessage = "Invalid server response";
            return false;
        }
        userId = Long.parseLong(params[0]);
        ssid = params[1];
        rank = Integer.parseInt(params[2]);
        score = Long.parseLong(params[3]);
        pp = Float.parseFloat(params[4]);
        accuracy = Float.parseFloat(params[5]);
        this.username = params[6];
        if (params.length >= 8) {
            avatarURL = params[7];
        } else {
            avatarURL = "";
        }
        profileBannerURL = getProfileBannerURL(userId);

        return true;
    }

    boolean tryToLogIn() throws OnlineManagerException {
        if (logIn(username, password) == false) {
            stayOnline = false;
            return false;
        }
        return true;
    }

    public boolean sendRecord(BeatmapInfo beatmap, String scoreData, String replayPath) throws OnlineManagerException {
        if (OFFLINE_MODE) {
            Debug.i("OnlineManager: score submission skipped, offline build.");
            failMessage = "";
            return false;
        }

        Debug.i("Sending record...");

        File replayFile = new File(replayPath);
        if (!replayFile.exists()) {
            failMessage = "Replay file not found";
            Debug.e("Replay file not found");
            return false;
        }

        var post = new FormDataPostBuilder();
        post.addParam("userID", String.valueOf(userId));
        post.addParam("ssid", ssid);
        post.addParam("filename", beatmap.getFullBeatmapName().trim());
        post.addParam("hash", beatmap.getMD5());
        post.addParam("data", scoreData);
        post.addParam("version", onlineVersion);

        post.addParam("cs", String.valueOf(beatmap.getCircleSize()));
        post.addParam("ar", String.valueOf(beatmap.getApproachRate()));
        post.addParam("od", String.valueOf(beatmap.getOverallDifficulty()));
        post.addParam("hp", String.valueOf(beatmap.getHpDrainRate()));

        MediaType replayMime = MediaType.parse("application/octet-stream");
        RequestBody replayFileBody = RequestBody.create(replayFile, replayMime);

        post.addParam("replayFile", replayFile.getName(), replayFileBody);
        post.addParam("replayFileChecksum", FileUtils.getSHA256Checksum(replayFile));

        ArrayList<String> response = sendRequest(post, endpoint + "submit.php");

        if (response == null) {
            return false;
        }

        if (failMessage.equals("Invalid record data"))
            return false;

        if (response.size() < 2) {
            failMessage = "Invalid server response";
            return false;
        }

        String[] resp = response.get(1).split("\\s+");
        if (resp.length < 4) {
            failMessage = "Invalid server response";
            return false;
        }

        rank = Integer.parseInt(resp[0]);
        score = Long.parseLong(resp[1]);
        accuracy = Float.parseFloat(resp[2]);
        mapRank = Integer.parseInt(resp[3]);
        pp = Float.parseFloat(resp[4]);

        return true;
    }

    public ArrayList<String> getTop(final String hash) throws OnlineManagerException {
        if (OFFLINE_MODE) {
            return new ArrayList<>();
        }

        PostBuilder post = new URLEncodedPostBuilder();
        post.addParam("hash", hash);
        post.addParam("uid", String.valueOf(userId));

        if (Config.getBeatmapLeaderboardScoringMode() == BeatmapLeaderboardScoringMode.PP) {
            post.addParam("type", "pp");
        } else {
            post.addParam("type", "score");
        }

        ArrayList<String> response = sendRequest(post, endpoint + "getrank.php");

        if (response == null) {
            return new ArrayList<>();
        }

        response.remove(0);

        return response;
    }

    public RankedStatus getBeatmapStatus(String md5) throws OnlineManagerException {
        if (OFFLINE_MODE) {
            return null;
        }

        return null;
    }

    public boolean loadAvatarToTextureManager() {
        return loadAvatarToTextureManager(avatarURL);
    }

    public boolean loadProfileBannerToTextureManager() {
        return loadProfileBannerToTextureManager(profileBannerURL);
    }

    public boolean loadAvatarToTextureManager(String avatarURL) {
        if (OFFLINE_MODE) {
            return false;
        }

        if (avatarURL == null || avatarURL.isEmpty()) return false;

        String filename = MD5Calculator.getStringMD5(avatarURL);
        Debug.i("Loading avatar from " + avatarURL);
        File picfile = new File(Config.getCachePath(), filename);
        OnlineFileOperator.downloadFile(avatarURL, picfile.getAbsolutePath(), true);

        var bitmap = loadFileToBitmap(picfile);
        int imageWidth = 0, imageHeight = 0;

        if (bitmap != null) {
            imageWidth = bitmap.getWidth();
            imageHeight = bitmap.getHeight();
        }

        if (imageWidth * imageHeight > 0) {
            // Avatar has been cached locally
            ResourceManager.getInstance().loadHighQualityFile(filename, picfile);
            if (ResourceManager.getInstance().getAvatarTextureIfLoaded(avatarURL) != null) {
                return true;
            }
        }

        return false;
    }

    public boolean loadProfileBannerToTextureManager(String bannerURL) {
        if (OFFLINE_MODE) {
            return false;
        }

        if (bannerURL == null || bannerURL.isEmpty()) return false;

        if (ResourceManager.getInstance().getProfileBannerTextureIfLoaded(bannerURL) != null) {
            return true;
        }

        String filename = MD5Calculator.getStringMD5(bannerURL);
        Debug.i("Loading profile banner from " + bannerURL);
        File bannerFile = new File(Config.getCachePath(), filename);
        OnlineFileOperator.downloadFile(bannerURL, bannerFile.getAbsolutePath(), true);

        var bitmap = loadFileToBitmap(bannerFile);
        int imageWidth = 0, imageHeight = 0;

        if (bitmap != null) {
            imageWidth = bitmap.getWidth();
            imageHeight = bitmap.getHeight();
        }

        if (imageWidth * imageHeight <= 0) {
            return false;
        }

        ResourceManager.getInstance().loadHighQualityFile(filename, bannerFile);
        return ResourceManager.getInstance().getProfileBannerTextureIfLoaded(bannerURL) != null;
    }

    private Bitmap loadFileToBitmap(File file) {
        if (!file.exists()) {
            return null;
        }

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            return BitmapFactory.decodeFile(file.getPath());
        } catch (NullPointerException e) {
            return null;
        }
    }

    public String getScorePack(int playid) throws OnlineManagerException {
        if (OFFLINE_MODE) {
            return "";
        }

        PostBuilder post = new URLEncodedPostBuilder();
        post.addParam("playID", String.valueOf(playid));

        if (Config.getBeatmapLeaderboardScoringMode() == BeatmapLeaderboardScoringMode.PP) {
            post.addParam("type", "pp");
        } else {
            post.addParam("type", "score");
        }

        ArrayList<String> response = sendRequest(post, endpoint + "gettop.php");

        if (response == null || response.size() < 2) {
            return "";
        }

        return response.get(1);
    }

    public String getFailMessage() {
        return failMessage;
    }

    public long getRank() {
        return rank;
    }

    public long getScore() {
        return score;
    }

    public float getPP() {
        return pp;
    }

    public float getAccuracy() {
        return accuracy;
    }

    public String getAvatarURL() {
        return avatarURL;
    }

    public String getProfileBannerURL() {
        return profileBannerURL;
    }

    public String getUsername() {
        return username;
    }

    public long getUserId() {
        return userId;
    }

    public String getSessionId() {
        return ssid;
    }

    public String getPassword() {
        return password;
    }

    public String getDeviceID() {
        return deviceID;
    }

    public boolean isStayOnline() {
        return !OFFLINE_MODE && stayOnline;
    }

    public void setStayOnline(boolean stayOnline) {
        if (OFFLINE_MODE) {
            this.stayOnline = false;
            return;
        }

        this.stayOnline = stayOnline;
    }

    public int getMapRank() {
        return mapRank;
    }

    public static class OnlineManagerException extends Exception {
        private static final long serialVersionUID = -5703212596292949401L;

        public OnlineManagerException(final String message, final Throwable cause) {
            super(message, cause);
        }

        public OnlineManagerException(final String message) {
            super(message);
        }
    }

}
