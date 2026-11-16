package com.codex.autobrightness;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/** Tiny in-process status channel; it schedules UI work only while an Activity is visible. */
final class StatusBus {
    /** The latest immutable snapshot remains available when the interface is reopened. */
    static volatile Snapshot latest = new Snapshot(-1, -1, -1, "尚未启动", false, 0, 0, 0);
    /** Visible UI listeners, removed in Activity.onStop to prevent retained screens. */
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Main-thread dispatcher for native widgets; no repeating timer is used. */
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Coalesce multiple sensor updates into one scheduled screen refresh. */
    private static boolean pending;
    /** Receive a snapshot only while the screen has explicitly subscribed. */
    interface Listener { void changed(Snapshot snapshot); }
    /** Immutable values displayed by the launcher Activity. */
    static final class Snapshot {
        /** Most recent raw illumination, or -1 before a valid reading. */
        final int light;
        /** Confirmed factory feedback, or -1 until the interface connects. */
        final int actual;
        /** Confirmed automatic target, or -1 before calculation. */
        final int target;
        /** Concise human-readable current state or connection problem. */
        final String message;
        /** Whether the current vehicle service connection is usable. */
        final boolean connected;
        /** Manual-pause deadline; Long.MAX_VALUE means until next boot/wake. */
        final long pauseUntil;
        /** Original timestamp of the latest valid light sample. */
        final long sampleTimestamp;
        /** Count of received valid light changes, maintained only in memory. */
        final long samples;
        /** Capture controller state without exposing its mutable worker-thread fields. */
        Snapshot(int light, int actual, int target, String message, boolean connected,
                long pauseUntil, long sampleTimestamp, long samples) {
            this.light = light; this.actual = actual; this.target = target; this.message = message;
            this.connected = connected; this.pauseUntil = pauseUntil;
            this.sampleTimestamp = sampleTimestamp; this.samples = samples;
        }
        /** Encode the exact in-memory UI state for an event-driven cross-package status callback. */
        JSONObject json() {
            return BridgeProtocol.object("light", light, "actual", actual, "target", target,
                    "message", message, "connected", connected, "pauseUntil", pauseUntil,
                    "sampleTimestamp", sampleTimestamp, "samples", samples);
        }
        /** Decode a validated engine reply without initiating any vehicle or periodic status reads. */
        static Snapshot read(JSONObject json) throws Exception {
            return new Snapshot(json.getInt("light"), json.getInt("actual"), json.getInt("target"),
                    json.getString("message"), json.getBoolean("connected"), json.getLong("pauseUntil"),
                    json.getLong("sampleTimestamp"), json.getLong("samples"));
        }
    }
    /** Add one visible Activity and synchronously deliver the cached state on its UI thread. */
    static void add(Listener listener) { listeners.addIfAbsent(listener); listener.changed(latest); }
    /** Remove a stopped Activity so background sensor events produce no interface refreshes. */
    static void remove(Listener listener) { listeners.remove(listener); }
    /** Cache state and coalesce visible refreshes; do nothing on the main thread when no UI is open. */
    static synchronized void publish(Snapshot snapshot) {
        latest = snapshot;
        if (listeners.isEmpty() || pending) return;
        pending = true;
        main.postDelayed(() -> {
            synchronized (StatusBus.class) { pending = false; }
            for (Listener listener : listeners) listener.changed(latest);
        }, 250);
    }
}
