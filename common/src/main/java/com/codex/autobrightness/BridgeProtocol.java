package com.codex.autobrightness;

import android.content.ComponentName;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;
import org.json.JSONObject;

/** Small native Messenger protocol; no generated IDL or additional runtime dependency is used. */
final class BridgeProtocol {
    /** Public settings package retains the existing desktop icon and legacy app-private settings. */
    static final String UI_PACKAGE = "com.codex.autobrightness";
    /** Independent engine identity; deliberately does not share the UI package's UID. */
    static final String ENGINE_PACKAGE = "com.codex.autobrightness.engine";
    /** Explicit service component prevents another package from resolving configuration requests. */
    static final ComponentName SERVICE = new ComponentName(ENGINE_PACKAGE, "com.codex.autobrightness.BrightnessService");
    /** Wire protocol revision, independent of the release version displayed to the user. */
    static final int WIRE_VERSION = 1;
    /** Register one visible UI callback and migrate legacy configuration if the engine is uninitialized. */
    static final int REGISTER = 1;
    /** Atomically update validated engine-owned configuration. */
    static final int CONFIGURE = 2;
    /** Clear manual/wake pauses and resume according to the supplied enabled setting. */
    static final int RESUME = 3;
    /** Apply one explicit manual brightness value through the existing native controller. */
    static final int MANUAL = 4;
    /** Release UI callbacks when the settings page goes to the background. */
    static final int UNREGISTER = 5;
    /** Reply marker; arg1 preserves the originating request ID, zero denotes an unsolicited status push. */
    static final int REPLY = 100;
    /** A JSON payload keeps command parameters and complete replies directly inspectable. */
    static final String PAYLOAD = "payload";
    /** The initial configuration extra initializes a newly installed engine without a temporary default ramp. */
    static final String INITIAL_CONFIG = "initial_config";

    /** Build a structured object while preserving explicit JSON nulls. */
    static JSONObject object(Object... pairs) {
        JSONObject json = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) json.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        return json;
    }
    /** Create a native message with a bounded JSON envelope and an optional one-way reply endpoint. */
    static Message message(int operation, int requestId, JSONObject payload, Messenger replyTo) {
        Message message = Message.obtain(null, operation); message.arg1 = requestId; message.replyTo = replyTo;
        Bundle data = new Bundle(); data.putString(PAYLOAD, payload.toString()); message.setData(data); return message;
    }
    /** Parse only small trusted-protocol envelopes and reject incompatible or oversized commands. */
    static JSONObject payload(Message message) throws Exception {
        String json = message.getData().getString(PAYLOAD);
        if (json == null || json.length() > 16384) throw new IllegalArgumentException("Invalid IPC payload length");
        JSONObject value = new JSONObject(json);
        if (value.getInt("protocol") != WIRE_VERSION) throw new IllegalArgumentException("IPC protocol mismatch");
        return value;
    }
    /** Log explicit request/reply JSON faithfully; unsolicited sensor status pushes are not logged per event. */
    static void log(String phase, int operation, int requestId, JSONObject request, Object response, Throwable error) {
        Log.i("AutoBrightnessBridge", object("phase", phase, "operation", operation, "requestId", requestId,
                "request", request, "response", response, "error", error == null ? null : error.toString()).toString());
    }
}
