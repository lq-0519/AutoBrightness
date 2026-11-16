package com.codex.autobrightness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import com.codex.autobrightness.engine.R;

/** Event-driven automatic brightness with a single low-priority control thread.
 * Steady state has no polling, repeating timers, wake lock, network access,
 * per-sample disk writes, or hidden Activity retained in the background.
 */
public final class BrightnessService extends Service {
    /** Low-importance foreground notification channel required by Android 9. */
    private static final String CHANNEL = "brightness_control";
    /** Single background-priority thread for all outgoing vehicle IPC and controller state. */
    private HandlerThread thread;
    /** Scheduler used only for event coalescing, confirmation, ramps, pauses, and reconnect backoff. */
    private Handler worker;
    /** Signature-protected UI endpoint; UI Binder death never stops this independently started service. */
    private EngineBridge bridge;
    /** Cached notification avoids recreating presentation objects for every IPC configuration update. */
    private Notification foregroundNotification;
    /** Track actual foreground start state separately from a visible configuration binding. */
    private volatile boolean foregroundStarted;
    /** Local user preferences; live sensor readings are never written here. */
    private Settings settings;
    /** Curve decoded once per configuration change. */
    private List<Settings.Point> curve;
    /** Vehicle adapter, null while sleeping or disconnected. */
    private VehicleApi api;
    /** Volatile lifecycle guard prevents new callback work during service teardown. */
    private volatile boolean destroying;
    /** Whether the display is currently interactive; no wake lock overrides this state. */
    private boolean awake;
    /** User choice to remain paused following wake until explicitly resumed. */
    private boolean wakePaused;
    /** Current connection availability on the worker thread. */
    private boolean connected;
    /** Incremented on disconnect to discard events from an old Binder connection. */
    private volatile int generation;
    /** Raw valid illumination, -1 until the first available sample. */
    private int light = -1;
    /** Most recent confirmed native brightness feedback. */
    private int actual = -1;
    /** Last command used as the starting point of a finite brightness ramp. */
    private int commanded = -1;
    /** Current confirmed automatic target; -1 before a valid light reading. */
    private int target = -1;
    /** Candidate quantized brightness pending temporal confirmation. */
    private int candidate = -1;
    /** Elapsed time when the current candidate began persisting. */
    private long candidateSince;
    /** Latest accepted light timestamp, used to ignore repeated cached startup samples. */
    private long lightTimestamp;
    /** Number of accepted real light samples, kept only in memory. */
    private long sampleCount;
    /** Time of connection, suppressing initial feedback as a manual adjustment. */
    private long connectedAt;
    /** Last outgoing brightness time, enforcing ramp spacing even when new light events arrive. */
    private long lastWriteAt;
    /** Exponential reconnect delay; only an unavailable interface causes retry work. */
    private long retryDelay = 1000;
    /** Current concise status message for the UI. */
    private String message = "等待车辆接口";
    /** Recently requested values allow delayed own echoes to be distinguished from manual changes. */
    private final int[] ownValues = new int[12];
    /** Elapsed timestamps paired with ownValues; entries expire after five seconds. */
    private final long[] ownTimes = new long[12];
    /** Next slot in the fixed-size own-command history, avoiding growing collections. */
    private int ownIndex;
    /** Latest light sample replaces older burst samples instead of building an unbounded queue. */
    private final AtomicReference<VehicleApi.Sample> pendingLight = new AtomicReference<>();
    /** Ensure at most one light-drain task is queued for a burst. */
    private final AtomicBoolean lightQueued = new AtomicBoolean();
    /** Preference listener is removed on destruction to avoid retaining the service. */
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    /** Screen lifecycle receiver is registered only while the controller service exists. */
    private BroadcastReceiver screenReceiver;
    /** Finite confirmation task is cancelled/replaced when light changes or automatic control pauses. */
    private final Runnable confirm = this::confirmCandidate;
    /** Finite ramp task stops completely when the native target is reached. */
    private final Runnable ramp = this::rampStep;
    /** Reconnect only after startup errors or binder death, never as a permanent health poll. */
    private final Runnable reconnect = this::connect;
    /** One deadline task resumes a timed manual pause without a countdown loop or alarm. */
    private final Runnable pauseEnded = () -> { if (!settings.paused()) { settings.clearPause(); scheduleTarget(); } };

    /** Ask Android to start the foreground service without performing IPC on the calling UI thread. */
    static void start(Context context, String action) {
        Intent intent = new Intent(context, BrightnessService.class);
        if (action != null) intent.setAction(action);
        context.startForegroundService(intent);
    }

    /** Enter foreground promptly, create one control thread, and subscribe to local lifecycle changes. */
    @Override public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "自动亮度后台运行", NotificationManager.IMPORTANCE_MIN);
        channel.setSound(null, null); channel.enableVibration(false); channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        Intent settingsScreen = new Intent().setClassName(BridgeProtocol.UI_PACKAGE, BridgeProtocol.UI_PACKAGE + ".MainActivity");
        PendingIntent open = PendingIntent.getActivity(this, 0, settingsScreen,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        foregroundNotification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_sun)
                .setContentTitle("随光亮度").setContentText("自动亮度后台服务 · 点此打开设置")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();
        thread = new HandlerThread("BrightnessControl", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start(); worker = new Handler(thread.getLooper());
        bridge = new EngineBridge(this, settings, worker, this::command);
        awake = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive();
        preferenceListener = (prefs, key) -> {
            if (!destroying) worker.post(() -> reload(key));
        };
        settings.prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        screenReceiver = new BroadcastReceiver() {
            /** Release subscriptions on screen-off and restore only when the user's wake policy permits. */
            @Override public void onReceive(Context context, Intent intent) {
                if (destroying) return;
                boolean isOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                worker.post(() -> screenChanged(isOn));
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF); registerReceiver(screenReceiver, filter);
    }

    /** Start foreground independently of the UI binding; initialize from retained legacy settings only once. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, foregroundNotification); foregroundStarted = true;
        String initial = intent == null ? null : intent.getStringExtra(BridgeProtocol.INITIAL_CONFIG);
        worker.post(() -> {
            try {
                if (!settings.initialized() && initial != null) settings.importConfig(new JSONObject(initial), true, true);
                curve = settings.points(); reload("points");
            } catch (Exception error) {
                message = "设置迁移失败，请重新打开设置：" + error.getMessage();
                cancelControl(); disconnect(); publish(); stopForeground(true); foregroundStarted = false; stopSelf();
            }
        });
        return START_STICKY;
    }

    /** Android enforces the manifest's signature permission before exposing this Messenger endpoint. */
    @Override public IBinder onBind(Intent intent) { return bridge.binder(); }

    /** Releasing the UI removes observations only; the separately started service remains running. */
    @Override public boolean onUnbind(Intent intent) { bridge.unbound(); return false; }

    /** Apply one verified IPC command on the existing control thread.
     * The engine owns all settings after initial migration. Routine edits never
     * overwrite its manual-pause deadline with a stale UI mirror. UI closure or
     * death does not enter this command path and therefore cannot stop control.
     */
    private void command(int operation, int brightness, JSONObject config) throws Exception {
        if (!settings.initialized()) {
            if (config == null) throw new IllegalArgumentException("等待首次设置迁移");
            settings.importConfig(config, true, true);
        } else if (operation != BridgeProtocol.REGISTER) {
            if (config == null) throw new IllegalArgumentException("缺少设置数据");
            settings.importConfig(config, true, false);
        }
        if (operation == BridgeProtocol.RESUME) { wakePaused = false; settings.clearPause(); }
        if (settings.enabled() && !foregroundStarted) start(this, null);
        curve = settings.points(); reload("points");
        if (operation == BridgeProtocol.MANUAL && settings.enabled()) manualRequest(brightness);
    }

    /** Reload only changed configuration; a light event never reparses saved rules. */
    private void reload(String key) {
        if (!settings.initialized() || !settings.enabled()) {
            cancelControl(); worker.removeCallbacks(reconnect); disconnect();
            message = settings.initialized() ? "自动控制已关闭" : "等待首次打开设置并迁移配置";
            publish(); stopForeground(true); foregroundStarted = false; stopSelf(); return;
        }
        if ("points".equals(key) || curve == null) curve = settings.points();
        if ("resume_on_wake".equals(key) && settings.resumeOnWake() && awake && wakePaused) {
            wakePaused = false;
        }
        cancelControl();
        if (!connected && awake && !wakePaused) connect(); else scheduleTarget();
    }

    /** Suspend all vehicle work while asleep; wake handling does not wake or reboot the head unit. */
    private void screenChanged(boolean isOn) {
        awake = isOn;
        if (!isOn) {
            cancelControl(); worker.removeCallbacks(reconnect); disconnect();
            message = "休眠中，已暂停读取"; publish(); return;
        }
        if (settings.pauseUntil() == Long.MAX_VALUE) settings.clearPause();
        wakePaused = !settings.resumeOnWake();
        if (wakePaused) { message = "唤醒后已暂停，点立即恢复继续"; publish(); }
        else connect();
    }

    /** Connect once and subscribe to change events; failed startup uses capped exponential backoff. */
    private void connect() {
        worker.removeCallbacks(reconnect);
        if (destroying || !settings.initialized() || !settings.enabled() || !awake || wakePaused || connected) return;
        disconnect();
        final int token = generation;
        message = "正在连接车辆接口"; publish();
        api = new VehicleApi(new VehicleApi.Listener() {
            /** Coalesce illumination bursts and discard all callbacks from a previous connection. */
            @Override public void onSample(VehicleApi.Sample sample) {
                if (destroying || token != generation) return;
                if (sample.property == VehicleApi.LIGHT) {
                    pendingLight.set(sample);
                    if (lightQueued.compareAndSet(false, true)) worker.postDelayed(() -> {
                        if (destroying || token != generation) return;
                        VehicleApi.Sample latest = pendingLight.getAndSet(null); lightQueued.set(false);
                        if (token == generation && latest != null) acceptLight(latest);
                    }, 250);
                } else if (sample.property == VehicleApi.DUTY) worker.post(() -> {
                    if (token == generation) acceptDuty(sample);
                });
            }
            /** Reconnect only on a real Binder death notification. */
            @Override public void onDisconnected() {
                if (!destroying) worker.post(() -> { if (token == generation) failed("车辆服务已重启，等待重连"); });
            }
            /** Surface a transport parsing error without allocating a persistent log buffer. */
            @Override public void onError(String error) {
                if (!destroying) worker.post(() -> { if (token == generation) {
                    cancelControl(); light = -1; target = -1; candidate = -1; message = error; publish();
                } });
            }
        });
        try {
            api.connect();
            actual = api.brightness(); commanded = actual;
            if (actual < 0 || actual > 100) throw new IllegalStateException("原厂亮度返回异常：" + actual);
            connectedAt = SystemClock.elapsedRealtime();
            connected = true; retryDelay = 1000; lightTimestamp = 0;
            api.subscribe();
            VehicleApi.Sample sample = api.get(VehicleApi.LIGHT);
            if (sample != null) acceptLight(sample);
            else { message = "等待第一条有效光照信号"; publish(); }
        } catch (Exception error) { failed("接口暂不可用：" + error.getMessage()); }
    }

    /** Accept available, ordered light samples; errors hold existing brightness rather than pretending it is dark. */
    private void acceptLight(VehicleApi.Sample sample) {
        if (!connected) return;
        try {
            int value = sample.nonnegativeInt();
            if (sample.timestamp <= 0) throw new IllegalArgumentException("光照样本尚未就绪");
            if (sample.timestamp < lightTimestamp) return;
            if (sample.timestamp == lightTimestamp && light >= 0) return;
            light = value; lightTimestamp = sample.timestamp; sampleCount++;
            scheduleTarget();
        } catch (IllegalArgumentException error) {
            cancelControl(); light = -1; target = -1; candidate = -1;
            message = "光照信号异常，保持当前亮度"; publish();
        }
    }

    /** Observe native feedback and pause on external changes, ignoring recent asynchronous own-command echoes. */
    private void acceptDuty(VehicleApi.Sample sample) {
        if (!connected) return;
        try {
            int brightness = sample.nonnegativeInt();
            if (brightness > 100) return;
            int previous = actual; actual = brightness;
            long now = SystemClock.elapsedRealtime();
            boolean own = false;
            for (int i = 0; i < ownValues.length; i++) {
                if (ownTimes[i] > 0 && now - ownTimes[i] <= 5000 && ownValues[i] == brightness) { own = true; break; }
            }
            if (previous >= 0 && brightness != previous && !own) {
                commanded = brightness;
                if (now - connectedAt > 2500 && settings.manualPolicy() != 2) {
                    settings.pauseManually(); cancelControl();
                    message = "检测到手动调节，自动控制已暂停"; schedulePauseEnd();
                } else scheduleTarget();
            }
            publish();
        } catch (IllegalArgumentException ignored) { /* Do not interpret missing feedback as a manual request. */ }
    }

    /** Start/restart finite confirmation only when a valid sample maps to a new native target. */
    private void scheduleTarget() {
        worker.removeCallbacks(pauseEnded);
        if (!connected || !awake || wakePaused || light < 0) { publish(); return; }
        if (settings.paused()) {
            cancelControl(); message = "手动接管中，自动控制已暂停"; schedulePauseEnd(); publish(); return;
        }
        int next = Settings.brightnessFor(light, curve);
        long now = SystemClock.elapsedRealtime();
        if (candidate != next) { candidate = next; candidateSince = now; }
        worker.removeCallbacks(confirm);
        if (target != next) {
            worker.removeCallbacks(ramp);
            worker.postDelayed(confirm, Math.max(0, settings.confirmationMs() - (now - candidateSince)));
            message = "自动运行 · 等待光照稳定";
        } else {
            message = "自动运行";
            if (commanded != target) {
                worker.removeCallbacks(ramp);
                worker.postDelayed(ramp, Math.max(0, settings.stepMs() - (now - lastWriteAt)));
            }
        }
        publish();
    }

    /** Adopt a stable target and start a finite ramp; no sensor poll is involved. */
    private void confirmCandidate() {
        if (!canControl()) return;
        target = candidate; message = "自动运行";
        worker.removeCallbacks(ramp); worker.post(ramp); publish();
    }

    /** Apply at most one native ten-point step, scheduling another only until the target is reached. */
    private void rampStep() {
        if (!canControl() || target < 10 || commanded == target) return;
        int start = Math.max(0, commanded);
        int next = start < target ? Math.min(target, start + 10) : Math.max(target, start - 10);
        next = Math.max(10, Math.min(100, Math.round(next / 10f) * 10));
        try {
            writeBrightness(next);
            if (commanded != target) worker.postDelayed(ramp, settings.stepMs());
        } catch (Exception error) { failed("亮度调整失败，保持现状：" + error.getMessage()); }
    }

    /** Verify lifecycle and manual-pause conditions immediately before any automatic brightness write. */
    private boolean canControl() {
        return !destroying && connected && awake && !wakePaused && settings.initialized() && settings.enabled() && !settings.paused() && light >= 0;
    }

    /** Record expected feedback before the synchronous setter so a fast echo is never treated as manual. */
    private void writeBrightness(int brightness) throws Exception {
        ownValues[ownIndex] = brightness; ownTimes[ownIndex] = SystemClock.elapsedRealtime();
        ownIndex = (ownIndex + 1) % ownValues.length;
        api.setBrightness(brightness); commanded = brightness;
        lastWriteAt = SystemClock.elapsedRealtime(); publish();
    }

    /** Set a user-selected value through the same native API, respecting the chosen takeover policy. */
    private void manualRequest(int brightness) {
        if (brightness < 10 || brightness > 100 || brightness % 10 != 0) return;
        if (!connected) { message = "车辆接口未连接，暂时无法手动调整"; publish(); return; }
        cancelControl(); settings.pauseManually();
        try {
            writeBrightness(brightness);
            message = settings.paused() ? "手动接管中，自动控制已暂停" : "手动亮度已应用";
            schedulePauseEnd();
            if (!settings.paused()) { candidate = -1; scheduleTarget(); }
        } catch (Exception error) { failed("手动亮度调整失败：" + error.getMessage()); }
    }

    /** Install one non-waking deadline callback for a timed manual pause, never a periodic countdown. */
    private void schedulePauseEnd() {
        worker.removeCallbacks(pauseEnded);
        long until = settings.pauseUntil();
        if (until > System.currentTimeMillis() && until != Long.MAX_VALUE)
            worker.postDelayed(pauseEnded, until - System.currentTimeMillis());
    }

    /** Cancel finite brightness work when disabled, asleep, overridden or disconnected. */
    private void cancelControl() {
        worker.removeCallbacks(confirm); worker.removeCallbacks(ramp); worker.removeCallbacks(pauseEnded);
    }

    /** Tear down subscriptions without touching the vehicle's current brightness. */
    private void disconnect() {
        generation++; connected = false; candidate = -1; target = -1; light = -1; lightTimestamp = 0;
        pendingLight.set(null); lightQueued.set(false);
        if (api != null) { api.close(); api = null; }
    }

    /** Keep existing brightness on errors and back off retries up to one minute while awake. */
    private void failed(String reason) {
        cancelControl(); disconnect(); message = reason; publish();
        worker.removeCallbacks(reconnect);
        if (!destroying && awake && !wakePaused && settings.enabled()) {
            worker.postDelayed(reconnect, retryDelay); retryDelay = Math.min(60000, retryDelay * 2);
        }
    }

    /** Publish a small in-memory snapshot; StatusBus avoids screen work while the Activity is stopped. */
    private void publish() {
        StatusBus.publish(new StatusBus.Snapshot(light, actual, target, message, connected,
                settings.pauseUntil(), lightTimestamp, sampleCount));
    }

    /** Expose a read-only, on-demand service diagnostic without opening the UI.
     * The normal dump reports in-memory state on the control thread. An explicit
     * --live argument additionally reads light and factory brightness once. No
     * timer, persistent log, exported receiver, or background polling is added.
     */
    @Override protected void dump(FileDescriptor descriptor, PrintWriter writer, String[] args) {
        if (worker == null || destroying) {
            writer.println(VehicleApi.object("service", "stopped").toString()); return;
        }
        boolean requestedLive = false;
        if (args != null) for (String arg : args) if ("--live".equals(arg)) requestedLive = true;
        final boolean live = requestedLive;
        CountDownLatch complete = new CountDownLatch(1);
        AtomicReference<JSONObject> result = new AtomicReference<>();
        boolean posted = worker.post(() -> {
            JSONObject data = VehicleApi.object("version", "1.1.0", "enginePackage", getPackageName(),
                    "initialized", settings.initialized(), "enabled", settings.enabled(),
                    "awake", awake, "wakePaused", wakePaused, "connected", connected,
                    "message", message, "rawLight", light, "lightTimestampNs", lightTimestamp,
                    "receivedLightChanges", sampleCount, "actualBrightness", actual,
                    "commandedBrightness", commanded, "targetBrightness", target,
                    "candidateBrightness", candidate, "manualPolicy", settings.manualPolicy(),
                    "manualPaused", settings.paused(), "pauseUntil", settings.pauseUntil());
            try {
                if (live && connected && api != null) {
                    VehicleApi.Sample sample = api.get(VehicleApi.LIGHT);
                    data.put("liveLightResponse", sample == null ? JSONObject.NULL : sample.json());
                    data.put("liveFactoryBrightness", api.brightness());
                }
            } catch (Exception error) {
                try { data.put("liveReadError", error.toString()); } catch (Exception ignored) { /* Valid literal key. */ }
            } finally { result.set(data); complete.countDown(); }
        });
        try {
            if (posted && complete.await(2, TimeUnit.SECONDS)) writer.println(result.get().toString());
            else writer.println(VehicleApi.object("error", "Controller state snapshot timed out").toString());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            writer.println(VehicleApi.object("error", error.toString()).toString());
        }
    }

    /** Release receivers, listeners, timers and Binder subscriptions without changing the user's brightness. */
    @Override public void onDestroy() {
        destroying = true;
        if (bridge != null) bridge.close();
        if (screenReceiver != null) unregisterReceiver(screenReceiver);
        if (preferenceListener != null) settings.prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            worker.post(() -> {
                disconnect(); message = settings.enabled() ? "后台服务已停止" : "自动控制已关闭";
                publish(); thread.quitSafely();
            });
        }
        stopForeground(true); super.onDestroy();
    }
}
