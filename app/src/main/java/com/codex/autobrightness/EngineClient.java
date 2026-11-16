package com.codex.autobrightness;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import org.json.JSONObject;

/** Visible-UI client for an independently installed controller.
 * It has no vehicle permission, timer, boot receiver, foreground service or
 * reconnect watchdog. Closing/killing this process leaves the engine running.
 */
final class EngineClient {
    /** Application context avoids retaining an Activity through Android's service connection registry. */
    private final Context context;
    /** Existing UI preferences provide one-time legacy migration and a local editable mirror. */
    private final Settings settings;
    /** Called only if the engine's authoritative settings differ from the local UI mirror. */
    private final Runnable configurationChanged;
    /** Current engine endpoint; cleared when Android reports process disconnection. */
    private Messenger remote;
    /** Whether Android accepted a binding that must be explicitly released in onStop. */
    private boolean bound;
    /** Whether the Activity is currently visible and may receive state/configuration callbacks. */
    private boolean active;
    /** Whether the registration/migration reply has arrived. */
    private boolean ready;
    /** Suppress echoing an engine snapshot back as a new user edit. */
    private boolean importing;
    /** Keep pre-connection edits until the first authoritative registration reply. */
    private boolean pendingConfig;
    /** Latest explicit resume/manual action queued while the service is connecting. */
    private int pendingAction;
    /** Brightness belonging to the pending manual command. */
    private int pendingBrightness = -1;
    /** Monotonic request IDs associate asynchronous full replies with their latest edit. */
    private int sequence;
    /** The newest configuration-bearing request; older replies cannot overwrite newer local edits. */
    private int latestWrite;
    /** Whether a configuration-bearing reply remains outstanding. */
    private boolean awaitingWrite;
    /** Local preference listener exists only while the UI is visible. */
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences;
    /** Android Messenger handles status callbacks without another thread or a polling loop. */
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper()) {
        /** Apply an explicit reply or unsolicited status snapshot only while this screen remains visible. */
        @Override public void handleMessage(Message message) { receive(message); }
    });
    /** Event-driven Binder reconnection is delegated to Android's existing binding lifecycle. */
    private final ServiceConnection connection = new ServiceConnection() {
        /** Register visible status delivery and submit the retained legacy settings for first initialization. */
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!active) return;
            remote = new Messenger(binder); ready = false;
            send(BridgeProtocol.REGISTER, -1);
        }
        /** Surface a real engine process loss; do not install a repeating reconnect task. */
        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null; ready = false; failure("后台服务连接已断开，等待系统恢复");
        }
        /** An updated or removed engine requires a fresh visible-page connection. */
        @Override public void onBindingDied(ComponentName name) {
            remote = null; ready = false; failure("后台服务已更新，请重新打开设置页面");
        }
    };

    /** Keep application-only dependencies and a removable configuration refresh callback. */
    EngineClient(Context context, Settings settings, Runnable configurationChanged) {
        this.context = context.getApplicationContext(); this.settings = settings;
        this.configurationChanged = configurationChanged;
        preferences = (prefs, key) -> {
            if (!active || importing || !isConfigurationKey(key)) return;
            pendingConfig = true;
            settings.putBoolean("pending_engine_sync", true);
            if (ready) send(BridgeProtocol.CONFIGURE, -1);
        };
    }
    /** Recognize only user configuration keys, not arbitrary internal preference changes. */
    private boolean isConfigurationKey(String key) {
        return "enabled".equals(key) || "autostart".equals(key) || "resume_on_wake".equals(key)
                || "sensitivity".equals(key) || "manual_policy".equals(key) || "points".equals(key);
    }

    /** Start the independent service explicitly, then bind only for this visible settings session. */
    void open() {
        if (active) return;
        active = true; ready = false;
        pendingConfig = settings.prefs.getBoolean("pending_engine_sync", false);
        settings.prefs.registerOnSharedPreferenceChangeListener(preferences);
        try {
            if (context.getPackageManager().checkSignatures(BridgeProtocol.UI_PACKAGE, BridgeProtocol.ENGINE_PACKAGE)
                    != PackageManager.SIGNATURE_MATCH) throw new SecurityException("后台服务未安装或签名不匹配");
            Intent engine = new Intent().setComponent(BridgeProtocol.SERVICE)
                    .putExtra(BridgeProtocol.INITIAL_CONFIG, settings.exportConfig().toString());
            context.startForegroundService(engine);
            bound = context.bindService(new Intent().setComponent(BridgeProtocol.SERVICE), connection, Context.BIND_AUTO_CREATE);
            if (!bound) throw new IllegalStateException("后台服务连接失败，请确认两个安装包均已安装");
        } catch (RuntimeException error) { failure(error.getMessage()); }
    }

    /** Release only UI observations; never stop the other package's independently started service. */
    void close() {
        if (!active) return;
        if (remote != null) send(BridgeProtocol.UNREGISTER, -1);
        active = false; ready = false; remote = null;
        settings.prefs.unregisterOnSharedPreferenceChangeListener(preferences);
        if (bound) { context.unbindService(connection); bound = false; }
    }
    /** Queue or send explicit resume after the user's enabled setting has been saved locally. */
    void resume() { action(BridgeProtocol.RESUME, -1); }
    /** Queue or send one user-released manual slider value. */
    void manual(int brightness) { action(BridgeProtocol.MANUAL, brightness); }
    /** Retain at most one explicit action during connection establishment. */
    private void action(int operation, int brightness) {
        if (ready && remote != null) send(operation, brightness);
        else { pendingAction = operation; pendingBrightness = brightness; }
    }

    /** Send complete command parameters once and faithfully log transport completion/errors in finally. */
    private void send(int operation, int brightness) {
        if (remote == null) return;
        int requestId = ++sequence;
        JSONObject payload = BridgeProtocol.object("protocol", BridgeProtocol.WIRE_VERSION);
        try {
            if (operation != BridgeProtocol.UNREGISTER) payload.put("config", settings.exportConfig());
            if (operation == BridgeProtocol.MANUAL) payload.put("brightness", brightness);
        } catch (Exception error) { failure(error.toString()); return; }
        Throwable failure = null;
        try {
            if (operation != BridgeProtocol.REGISTER && operation != BridgeProtocol.UNREGISTER) {
                latestWrite = requestId; awaitingWrite = true; pendingConfig = false;
            }
            remote.send(BridgeProtocol.message(operation, requestId, payload, replies));
        } catch (RemoteException error) { failure = error; ready = false; failure("后台服务暂时不可用：" + error); }
        finally { BridgeProtocol.log("request", operation, requestId, payload, null, failure); }
    }

    /** Apply ordered replies without writing configuration for ordinary sensor status pushes. */
    private void receive(Message message) {
        if (!active || message.what != BridgeProtocol.REPLY) return;
        JSONObject payload = null;
        Throwable failure = null;
        try {
            payload = BridgeProtocol.payload(message);
            if (!payload.isNull("error")) throw new IllegalStateException(payload.getString("error"));
            if (payload.has("state")) StatusBus.publish(StatusBus.Snapshot.read(payload.getJSONObject("state")));
            boolean initial = !ready && message.arg1 != 0;
            if (initial) ready = true;
            boolean newestWrite = awaitingWrite && message.arg1 == latestWrite;
            if (newestWrite) {
                awaitingWrite = false;
                settings.putBoolean("pending_engine_sync", false);
            }
            if (payload.has("config") && !pendingConfig && !awaitingWrite) {
                JSONObject config = payload.getJSONObject("config");
                if (!settings.exportConfig().toString().equals(config.toString())) {
                    importing = true;
                    try { settings.importConfig(config, false, true); }
                    finally { importing = false; }
                    configurationChanged.run();
                }
            }
            if (initial) {
                if (pendingConfig) send(BridgeProtocol.CONFIGURE, -1);
                if (pendingAction != 0) {
                    int operation = pendingAction, brightness = pendingBrightness;
                    pendingAction = 0; pendingBrightness = -1; send(operation, brightness);
                }
            }
        } catch (Exception error) { failure = error; failure("后台服务返回异常：" + error.getMessage()); }
        finally {
            if (message.arg1 != 0) BridgeProtocol.log("response", BridgeProtocol.REPLY, message.arg1, null, payload, failure);
        }
    }
    /** Publish a UI-only connection problem without touching the engine or vehicle brightness. */
    private void failure(String message) {
        StatusBus.Snapshot old = StatusBus.latest;
        StatusBus.publish(new StatusBus.Snapshot(old.light, old.actual, old.target,
                message == null ? "后台服务暂不可用" : message, false, old.pauseUntil, old.sampleTimestamp, old.samples));
    }
}
