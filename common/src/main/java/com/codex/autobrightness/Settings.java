package com.codex.autobrightness;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Local configuration; only deliberate setting changes write to persistent storage. */
final class Settings {
    /** Private preferences: authoritative in the engine, a migration/UI mirror in the settings package. */
    final SharedPreferences prefs;
    /** Initial illustrative curve, editable after the user's daylight measurements. */
    private static final String DEFAULT_POINTS = "[[0,20],[50,40],[200,70],[500,100]]";

    /** Open the app-private settings without retaining an Activity reference. */
    Settings(Context context) { prefs = context.getApplicationContext().getSharedPreferences("settings", 0); }
    /** Whether the controller is enabled, independently of boot autostart. */
    boolean enabled() { return prefs.getBoolean("enabled", true); }
    /** Whether a completed vehicle boot should start the controller. */
    boolean autostart() { return prefs.getBoolean("autostart", true); }
    /** Whether screen wake resumes subscriptions and automatic control. */
    boolean resumeOnWake() { return prefs.getBoolean("resume_on_wake", true); }
    /** Sensitivity preset: 0 steady, 1 balanced, 2 responsive. */
    int sensitivity() { return Math.max(0, Math.min(2, prefs.getInt("sensitivity", 1))); }
    /** Manual policy: 0 ten minutes, 1 until next boot/wake, 2 keep automatic control. */
    int manualPolicy() { return Math.max(0, Math.min(2, prefs.getInt("manual_policy", 0))); }
    /** Wall-clock deadline persisted only when manual takeover starts or ends. */
    long pauseUntil() { return prefs.getLong("pause_until", 0); }
    /** Persist a user-facing boolean setting asynchronously. */
    void putBoolean(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    /** Persist a user-facing integer choice asynchronously. */
    void putInt(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    /** Clear a manual pause on explicit resume or a new vehicle boot/wake session. */
    void clearPause() { prefs.edit().putLong("pause_until", 0).apply(); }
    /** Start a manual pause; Long.MAX_VALUE means until the next boot or wake. */
    void pauseManually() {
        if (manualPolicy() == 2) return;
        prefs.edit().putLong("pause_until", manualPolicy() == 1 ? Long.MAX_VALUE : System.currentTimeMillis() + 600000L).apply();
    }
    /** Check a persisted pause without starting a periodic countdown timer. */
    boolean paused() { return pauseUntil() > System.currentTimeMillis(); }
    /** Require the candidate brightness to persist this long before adopting it. */
    long confirmationMs() { return new long[]{3500, 1500, 400}[sensitivity()]; }
    /** Space ten-point native brightness steps; no timer remains once the target is reached. */
    long stepMs() { return new long[]{1400, 800, 400}[sensitivity()]; }

    /** Whether the engine has received the user's initial configuration and may autostart. */
    boolean initialized() { return prefs.getBoolean("engine_initialized", false); }

    /** Export only configuration, never device data or sensor history, for same-signature IPC. */
    JSONObject exportConfig() {
        JSONArray values = new JSONArray();
        for (Point point : points()) values.put(new JSONArray().put(point.light).put(point.brightness));
        return BridgeProtocol.object("schema", 1, "enabled", enabled(), "autostart", autostart(),
                "resume_on_wake", resumeOnWake(), "sensitivity", sensitivity(),
                "manual_policy", manualPolicy(), "pause_until", pauseUntil(), "points", values);
    }

    /** Validate a whole configuration before applying it atomically.
     * Normal UI edits preserve the engine's current manual pause. Initial legacy
     * migration and received engine snapshots may copy it explicitly. Identical
     * snapshots do not cause preference writes or change notifications.
     */
    void importConfig(JSONObject json, boolean markInitialized, boolean copyPause) throws Exception {
        if (json.getInt("schema") != 1) throw new IllegalArgumentException("设置版本不兼容");
        boolean enabled = json.getBoolean("enabled"), autostart = json.getBoolean("autostart");
        boolean resume = json.getBoolean("resume_on_wake");
        int sensitivity = json.getInt("sensitivity"), manual = json.getInt("manual_policy");
        long pause = json.getLong("pause_until");
        String points = json.getJSONArray("points").toString();
        decode(points);
        if (sensitivity < 0 || sensitivity > 2 || manual < 0 || manual > 2 || pause < 0)
            throw new IllegalArgumentException("设置值超出允许范围");
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (enabled() != enabled) { editor.putBoolean("enabled", enabled); changed = true; }
        if (autostart() != autostart) { editor.putBoolean("autostart", autostart); changed = true; }
        if (resumeOnWake() != resume) { editor.putBoolean("resume_on_wake", resume); changed = true; }
        if (sensitivity() != sensitivity) { editor.putInt("sensitivity", sensitivity); changed = true; }
        if (manualPolicy() != manual) { editor.putInt("manual_policy", manual); changed = true; }
        if (copyPause && pauseUntil() != pause) { editor.putLong("pause_until", pause); changed = true; }
        if (!prefs.getString("points", DEFAULT_POINTS).equals(points)) { editor.putString("points", points); changed = true; }
        if (markInitialized && !initialized()) { editor.putBoolean("engine_initialized", true); changed = true; }
        if (changed) editor.apply();
    }

    /** A raw-light / native brightness pair; raw light intentionally has no claimed lux unit. */
    static final class Point {
        /** Raw nonnegative vehicle illumination reading. */
        final int light;
        /** Factory brightness percentage, restricted to visible ten-point steps. */
        final int brightness;
        /** Construct one immutable curve point. */
        Point(int light, int brightness) { this.light = light; this.brightness = brightness; }
    }

    /** Decode saved points and fall back to the built-in curve if stored content is malformed. */
    List<Point> points() {
        try { return decode(prefs.getString("points", DEFAULT_POINTS)); }
        catch (Exception ignored) {
            try { return decode(DEFAULT_POINTS); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
    }
    /** Validate a monotonic curve beginning at zero; clamp nothing silently. */
    static List<Point> validate(List<Point> input) {
        if (input.size() < 2 || input.size() > 12) throw new IllegalArgumentException("请保留 2～12 个对应点");
        List<Point> sorted = new ArrayList<>(input);
        Collections.sort(sorted, Comparator.comparingInt(point -> point.light));
        if (sorted.get(0).light != 0) throw new IllegalArgumentException("第一个光照值必须为 0");
        int lastLight = -1, lastBrightness = 0;
        for (Point point : sorted) {
            if (point.light < 0 || point.light > 1000000 || point.light <= lastLight)
                throw new IllegalArgumentException("光照值必须递增、不重复，范围为 0～1000000");
            if (point.brightness < 10 || point.brightness > 100 || point.brightness % 10 != 0)
                throw new IllegalArgumentException("亮度请填 10～100 之间的整十数");
            if (point.brightness < lastBrightness) throw new IllegalArgumentException("光照增加时，亮度不能降低");
            lastLight = point.light; lastBrightness = point.brightness;
        }
        return sorted;
    }
    /** Parse the small JSON curve only on configuration load, never per sensor event. */
    private static List<Point> decode(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        List<Point> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONArray pair = array.getJSONArray(i);
            result.add(new Point(pair.getInt(0), pair.getInt(1)));
        }
        return validate(result);
    }
    /** Store a fully validated curve in one preference update. */
    void savePoints(List<Point> points) {
        JSONArray array = new JSONArray();
        for (Point point : validate(points)) array.put(new JSONArray().put(point.light).put(point.brightness));
        prefs.edit().putString("points", array.toString()).apply();
    }
    /** Interpolate between user points, quantize to factory steps and cap beyond the last point. */
    static int brightnessFor(int light, List<Point> points) {
        Point previous = points.get(0);
        if (light <= previous.light) return previous.brightness;
        for (int i = 1; i < points.size(); i++) {
            Point next = points.get(i);
            if (light <= next.light) {
                double fraction = (double) (light - previous.light) / (next.light - previous.light);
                int brightness = (int) Math.round((previous.brightness + (next.brightness - previous.brightness) * fraction) / 10.0) * 10;
                return Math.max(10, Math.min(100, brightness));
            }
            previous = next;
        }
        return previous.brightness;
    }
}
