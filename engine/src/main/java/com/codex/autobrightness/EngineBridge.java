package com.codex.autobrightness;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import org.json.JSONObject;

/** Same-signature settings IPC, detached from the lifetime of the independent controller.
 * A single visible settings client is sufficient. Its Binder death removes only
 * UI callbacks: it never stops, restarts, or changes the brightness controller.
 */
final class EngineBridge {
    /** Application context is used only for caller signature/UID verification. */
    private final Context context;
    /** Engine-owned configuration, retained when the settings package is force-stopped. */
    private final Settings settings;
    /** All configuration/controller operations execute on the existing control thread. */
    private final Handler worker;
    /** Native main dispatcher owns Binder callback registration and UI status delivery. */
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Controller callback is never invoked merely because a settings client disconnects. */
    private final Controller controller;
    /** Only the currently visible settings page receives status pushes. */
    private Messenger client;
    /** Death link removes the UI callback even when SystemUI kills the settings process abruptly. */
    private IBinder.DeathRecipient clientDeath;
    /** Prevent late in-flight registration from retaining a destroyed engine service. */
    private boolean closed;
    /** Invalidate a registration reply if the UI unbound before its worker command completed. */
    private int clientEpoch;
    /** Status observer is registered only while a live settings client exists. */
    private final StatusBus.Listener observer = state -> push(state);
    /** Messenger supplies each incoming Message.sendingUid from the underlying Binder transaction. */
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
        /** Validate the exact package/signature before dispatching a bounded configuration command. */
        @Override public void handleMessage(Message message) { receive(message); }
    });

    /** Execute one verified command against the engine; incoming JSON is validated by Settings. */
    interface Controller { void execute(int operation, int brightness, JSONObject config) throws Exception; }

    /** Reuse the controller's handler rather than creating another background thread. */
    EngineBridge(Context context, Settings settings, Handler worker, Controller controller) {
        this.context = context.getApplicationContext(); this.settings = settings;
        this.worker = worker; this.controller = controller;
    }
    /** Expose the Messenger Binder only through the manifest's signature-protected service binding. */
    IBinder binder() { return endpoint.getBinder(); }

    /** Accept commands from only our settings package, signed by the same release key. */
    private boolean allowed(int uid) {
        try {
            PackageManager manager = context.getPackageManager();
            return uid == manager.getPackageUid(BridgeProtocol.UI_PACKAGE, 0)
                    && manager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    /** Capture mutable Message data before dispatch, preserving the full request and its reply correlation ID. */
    private void receive(Message message) {
        final int operation = message.what, requestId = message.arg1;
        final Messenger replyTo = message.replyTo;
        JSONObject request = null;
        try {
            if (closed || !allowed(message.sendingUid)) throw new SecurityException("Untrusted settings caller");
            request = BridgeProtocol.payload(message);
            if (operation < BridgeProtocol.REGISTER || operation > BridgeProtocol.UNREGISTER)
                throw new IllegalArgumentException("Unknown command " + operation);
            if (operation == BridgeProtocol.UNREGISTER) {
                clientEpoch++; detach();
                JSONObject response = result(null);
                reply(replyTo, requestId, response);
                BridgeProtocol.log("handled", operation, requestId, request, response, null); return;
            }
            final JSONObject captured = request;
            final int epoch = operation == BridgeProtocol.REGISTER ? ++clientEpoch : clientEpoch;
            worker.post(() -> {
                JSONObject response = null;
                Throwable failure = null;
                try {
                    controller.execute(operation, captured.optInt("brightness", -1), captured.optJSONObject("config"));
                    response = result(null);
                } catch (Exception error) { failure = error; response = result(error); }
                finally {
                    final JSONObject completed = response;
                    final boolean register = operation == BridgeProtocol.REGISTER && failure == null;
                    BridgeProtocol.log("handled", operation, requestId, captured, response, failure);
                    main.post(() -> {
                        reply(replyTo, requestId, completed);
                        if (register && !closed && epoch == clientEpoch) attach(replyTo);
                    });
                }
            });
        } catch (Exception error) {
            JSONObject response = error instanceof SecurityException
                    ? BridgeProtocol.object("protocol", BridgeProtocol.WIRE_VERSION, "error", error.toString())
                    : result(error);
            BridgeProtocol.log("handled", operation, requestId, request, response, error);
            reply(replyTo, requestId, response);
        }
    }

    /** Return complete configuration only for explicit commands; no per-light preference serialization is needed. */
    private JSONObject result(Throwable error) {
        return BridgeProtocol.object("protocol", BridgeProtocol.WIRE_VERSION, "version", "1.1.0",
                "initialized", settings.initialized(), "config", settings.exportConfig(),
                "state", StatusBus.latest.json(), "error", error == null ? null : error.toString());
    }
    /** Send an explicit command reply once; a dead UI cannot stop or relaunch the controller. */
    private void reply(Messenger target, int requestId, JSONObject payload) {
        if (target == null) return;
        Throwable failure = null;
        try { target.send(BridgeProtocol.message(BridgeProtocol.REPLY, requestId, payload, null)); }
        catch (RemoteException error) { failure = error; if (sameClient(target)) detach(); }
        finally { BridgeProtocol.log("reply", BridgeProtocol.REPLY, requestId, payload, null, failure); }
    }
    /** Attach only a live visible UI; clear any previous callback without affecting service start state. */
    private void attach(Messenger next) {
        detach();
        if (next == null || closed) return;
        client = next;
        final IBinder token = next.getBinder();
        clientDeath = () -> main.post(() -> {
            if (client != null && client.getBinder().equals(token)) { clientEpoch++; detach(); }
        });
        try {
            token.linkToDeath(clientDeath, 0);
            StatusBus.add(observer);
        } catch (RemoteException error) { detach(); }
    }
    /** Determine whether a failed reply belonged to the current UI callback. */
    private boolean sameClient(Messenger target) {
        return client != null && client.getBinder().equals(target.getBinder());
    }
    /** Push only state, and only while the UI is visible; sensor updates never write a settings mirror. */
    private void push(StatusBus.Snapshot state) {
        Messenger target = client;
        if (target == null || closed) return;
        JSONObject payload = BridgeProtocol.object("protocol", BridgeProtocol.WIRE_VERSION, "state", state.json(), "error", null);
        try { target.send(BridgeProtocol.message(BridgeProtocol.REPLY, 0, payload, null)); }
        catch (RemoteException error) { detach(); }
    }
    /** Remove UI callback/death links without stopping the independently started foreground service. */
    private void detach() {
        StatusBus.remove(observer);
        if (client != null && clientDeath != null) {
            try { client.getBinder().unlinkToDeath(clientDeath, 0); } catch (RuntimeException ignored) { /* Client already gone. */ }
        }
        client = null; clientDeath = null;
    }
    /** Release the endpoint observer during actual engine destruction. */
    void close() { closed = true; clientEpoch++; detach(); }
    /** Clear late callbacks when Android unbinds the only settings client, keeping controller start state intact. */
    void unbound() { clientEpoch++; detach(); }
}
