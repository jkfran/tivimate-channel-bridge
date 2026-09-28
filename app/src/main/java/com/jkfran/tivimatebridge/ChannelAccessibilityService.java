package com.jkfran.tivimatebridge;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * TiviMate Channel Bridge — reads the currently playing channel from TiviMate's on-screen
 * info bar / channel list using the Android accessibility API (no root) and POSTs it to a
 * Home Assistant webhook.
 *
 * Two detection paths:
 *   1) Zapping shows the info bar -> anchor on the stream-badge row (FPS/audio/resolution)
 *      and take the left-most non-badge text on that row as the channel name.
 *   2) Opening the list/guide (Back) and pressing OK fires VIEW_CLICKED -> read the clicked
 *      channel row's name.
 *
 * Config: put a config.properties in the app's external files dir to override defaults, e.g.
 *   adb push config.properties /sdcard/Android/data/com.jkfran.tivimatebridge/files/config.properties
 * with:
 *   ha_webhook_url=http://homeassistant.local:8123/api/webhook/tivimate_channel
 * Otherwise the compiled default below is used.
 */
public class ChannelAccessibilityService extends AccessibilityService {

    private static final String TAG = "TiviMateBridge";
    private static final String TVIMATE_PKG = "ar.tvplayer.tv";
    // Default HA webhook. Override at runtime via config.properties, or edit before building.
    private static final String DEFAULT_HA_WEBHOOK_URL =
            "http://homeassistant.local:8123/api/webhook/tivimate_channel";

    private static final Pattern FPS = Pattern.compile("(?i)\\b\\d+\\s*FPS\\b");
    private static final Pattern AUDIO = Pattern.compile("(?i)^(STEREO|MONO|DUAL|DOLBY.*|AC[-\\s]?3|E?AC3|AAC|MP2|DTS.*)$");
    private static final Pattern RES = Pattern.compile("(?i)^(FHD|UHD|4K|8K|HD|SD|\\d{3,4}p)$");
    private static final Pattern TIME = Pattern.compile("^\\d{1,2}:\\d{2}.*");
    private static final Pattern DURATION = Pattern.compile("(?i)^\\d+\\s*(min|h|hr|m)\\b.*");
    private static final Pattern DATECLOCK = Pattern.compile("(?i).*(mon|tue|wed|thu|fri|sat|sun|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec),?\\s.*");
    private static final Pattern NOINFO = Pattern.compile("(?i)^(no information|sin información|—|-)?$");
    // Optional soft preference: many IPTV providers prefix channels by country ("ES| ...").
    private static final Pattern PREFIX = Pattern.compile("^\\s*[A-Z]{2,4}\\s*\\|\\s*\\S.*");

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile String lastReported = null;
    private volatile String haWebhookUrl = DEFAULT_HA_WEBHOOK_URL;
    private long lastEventAt = 0;

    private static class Item { String text; Rect b; Item(String t, Rect r){text=t;b=r;} }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        loadConfig();
        Log.i(TAG, "connected; webhook=" + haWebhookUrl);
    }

    private void loadConfig() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File cfg = new File(dir, "config.properties");
            if (!cfg.exists()) return;
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(cfg)) { p.load(in); }
            String u = p.getProperty("ha_webhook_url");
            if (u != null && !u.trim().isEmpty()) haWebhookUrl = u.trim();
        } catch (Throwable t) { Log.w(TAG, "config load failed: " + t); }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!TVIMATE_PKG.contentEquals(event.getPackageName())) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String ch = channelFromNode(event.getSource());
            if (ch != null) reportIfNew(ch);
            return;
        }
        long now = System.currentTimeMillis();
        lastEventAt = now;
        main.postDelayed(() -> {
            if (System.currentTimeMillis() - lastEventAt < 120) return;
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;
                List<Item> items = new ArrayList<>();
                collect(root, items);
                String ch = extractFromInfoBar(items);
                if (ch != null) reportIfNew(ch);
            } catch (Throwable t) { Log.w(TAG, "scan error", t); }
        }, 150);
    }

    private void reportIfNew(String channel) {
        channel = channel.trim();
        if (channel.isEmpty() || channel.equals(lastReported)) return;
        lastReported = channel;
        Log.i(TAG, "CHANNEL=" + channel);
        report(channel);
    }

    private void collect(AccessibilityNodeInfo node, List<Item> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            Rect r = new Rect(); node.getBoundsInScreen(r);
            out.add(new Item(t.toString(), r));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (c != null) { collect(c, out); c.recycle(); }
        }
    }

    private boolean isChannelish(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 2 || s.length() > 60) return false;
        if (NOINFO.matcher(s).matches()) return false;
        if (FPS.matcher(s).find() || AUDIO.matcher(s).matches() || RES.matcher(s).matches()
                || TIME.matcher(s).matches() || DURATION.matcher(s).matches() || DATECLOCK.matcher(s).matches())
            return false;
        return true;
    }

    /** From a clicked (guide/list) node subtree, pick the channel name. Prefer a country-prefixed
     *  name; otherwise the first plausible non-badge/non-time text. */
    private String channelFromNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        List<Item> items = new ArrayList<>();
        collect(node, items);
        for (Item it : items) if (PREFIX.matcher(it.text.trim()).matches() && isChannelish(it.text)) return it.text.trim();
        for (Item it : items) if (isChannelish(it.text)) return it.text.trim();
        return null;
    }

    /** From the info bar, the channel name shares the row with the FPS/audio badge. */
    private String extractFromInfoBar(List<Item> items) {
        Item anchor = null;
        for (Item it : items) if (FPS.matcher(it.text).find()) { anchor = it; break; }
        if (anchor == null)
            for (Item it : items) if (AUDIO.matcher(it.text.trim()).matches()) { anchor = it; break; }
        if (anchor == null) return null;
        int row = anchor.b.centerY();
        Item best = null;
        for (Item it : items) {
            if (Math.abs(it.b.centerY() - row) > 40) continue;
            if (!isChannelish(it.text)) continue;
            if (best == null || it.b.left < best.b.left) best = it;
        }
        return best == null ? null : best.text.trim();
    }

    private void report(final String channel) {
        final String url = haWebhookUrl;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(4000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                String body = "{\"channel\":\"" + channel.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
                try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes("UTF-8")); }
                Log.i(TAG, "POST " + c.getResponseCode() + " channel=" + channel);
            } catch (Throwable t) { Log.w(TAG, "POST failed: " + t); }
            finally { if (c != null) c.disconnect(); }
        }).start();
    }

    @Override public void onInterrupt() { }
}
