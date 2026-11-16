package com.codex.autobrightness;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import org.json.JSONObject;

/** F202 Android 9 vehicle adapter using the exact installed Binder wire format.
 * No Android car implementation classes, copied vendor SDKs, or generated IDL
 * are packaged. Transaction IDs and parcel layouts match the inspected firmware.
 */
final class VehicleApi {
    /** Environment illumination property proven responsive in the flashlight experiment. */
    static final int LIGHT = 0x2140f180;
    /** Factory screen duty feedback, used to observe actual brightness/manual changes. */
    static final int DUTY = 0x2140f20f;
    /** Descriptor for resolving the existing named car services. */
    private static final String CAR = "android.car.ICar";
    /** Descriptor for reading and subscribing to vehicle properties. */
    private static final String PROPERTY = "android.car.hardware.property.ICarProperty";
    /** Descriptor for the factory brightness getter/setter. */
    private static final String POWER = "android.car.hardware.power.ICarPower";
    /** Descriptor enforced by the one-way property event callback. */
    private static final String EVENTS = "android.car.hardware.property.ICarPropertyEventListener";
    /** Property service obtained from the current car-service process. */
    private IBinder properties;
    /** Power service used only for brightness, never power-state listener registration. */
    private IBinder power;
    /** Root car binder retained for death notification and explicit unlinking. */
    private IBinder root;
    /** App callback; dispatch is handed to the service's single worker thread. */
    private final Listener listener;
    /** Whether a light subscription was successfully installed. */
    private boolean lightSubscribed;
    /** Whether the screen-duty feedback subscription was installed. */
    private boolean dutySubscribed;
    /** Detect vehicle-service restarts without an idle polling timer. */
    private final IBinder.DeathRecipient death;
    /** One shared Binder callback handles both subscriptions without extra worker threads. */
    private final Binder events = new Binder() {
        /** Decode the vendor's typed event list; incoming events are not written to persistent logs. */
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(EVENTS); return true; }
            if (code != 1) return super.onTransact(code, data, reply, flags);
            try {
                data.enforceInterface(EVENTS);
                int count = data.readInt();
                if (count < 0 || count > 512) throw new IllegalArgumentException("Invalid event count " + count);
                for (int i = 0; i < count; i++) {
                    if (data.readInt() == 0) continue;
                    int eventType = data.readInt();
                    String className = data.readString();
                    if (className == null) continue;
                    if (!"android.car.hardware.CarPropertyValue".equals(className))
                        throw new IllegalArgumentException("Unexpected event parcel " + className);
                    Sample sample = Sample.read(data);
                    if (eventType == 0) listener.onSample(sample);
                    else listener.onError("车辆信号返回错误，属性 0x" + Integer.toHexString(sample.property));
                }
            } catch (RuntimeException error) { listener.onError("车辆信号解析失败：" + error); }
            return true;
        }
    };

    /** Receive actual change events and connection errors without owning an Activity. */
    interface Listener {
        /** Deliver one property snapshot with its original status and vehicle timestamp. */
        void onSample(Sample sample);
        /** Notify transport loss so the controller can reconnect with bounded backoff. */
        void onDisconnected();
        /** Report an invalid event without crashing the Binder callback thread. */
        void onError(String message);
    }

    /** Immutable scalar vehicle reply, retaining every field supplied by the interface. */
    static final class Sample {
        /** Vendor property ID, preserved without remapping. */
        final int property;
        /** Area returned by the server; global subscriptions request area zero. */
        final int area;
        /** Original status: zero means available, one unavailable, two error. */
        final int status;
        /** Monotonic vehicle sample timestamp, not the host receive time. */
        final long timestamp;
        /** Original scalar value, including Boolean rather than an invented numeric conversion. */
        final Object value;
        /** Construct a faithful immutable vehicle sample. */
        Sample(int property, int area, int status, long timestamp, Object value) {
            this.property = property; this.area = area; this.status = status; this.timestamp = timestamp; this.value = value;
        }
        /** Decode exactly the CarPropertyValue parcel format in F202_D3_20240816092844. */
        static Sample read(Parcel parcel) {
            int property = parcel.readInt(), area = parcel.readInt(), status = parcel.readInt();
            long timestamp = parcel.readLong();
            String type = parcel.readString();
            if (!"java.lang.Integer".equals(type) && !"java.lang.Boolean".equals(type)
                    && !"java.lang.Float".equals(type) && !"java.lang.Long".equals(type))
                throw new IllegalArgumentException("Unexpected scalar type " + type);
            return new Sample(property, area, status, timestamp, parcel.readValue(null));
        }
        /** Expose a valid nonnegative integer without treating unavailable samples as darkness. */
        int nonnegativeInt() {
            if (status != 0 || !(value instanceof Integer) || (Integer) value < 0)
                throw new IllegalArgumentException("Unavailable or invalid property 0x" + Integer.toHexString(property));
            return (Integer) value;
        }
        /** Serialize every returned field for faithful local IPC request/response diagnostics. */
        JSONObject json() { return object("propertyId", property, "areaId", area, "status", status,
                "timestampNs", timestamp, "value", value); }
    }

    /** Hold only the worker callback; each connection obtains fresh service binders. */
    VehicleApi(Listener listener) {
        this.listener = listener;
        death = listener::onDisconnected;
        events.attachInterface(null, EVENTS);
    }

    /** Resolve the installed car services, with faithful JSON logging even on reflection/permission errors. */
    void connect() throws Exception {
        Throwable failure = null;
        try {
            root = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                    .invoke(null, "car_service");
            if (root == null) throw new IllegalStateException("车辆服务尚未启动");
        } catch (Exception error) { failure = error; throw error; }
        finally { log("getService", object("name", "car_service"), root == null ? null : root.toString(), failure); }
        root.linkToDeath(death, 0);
        properties = service("property");
        power = service("power");
        if (properties == null || power == null) throw new IllegalStateException("车辆亮度服务尚未就绪");
    }
    /** Resolve one named child binder using ICar transaction 2. */
    private IBinder service(String name) throws Exception {
        return call(root, CAR, 2, object("serviceName", name), p -> p.writeString(name), Parcel::readStrongBinder);
    }
    /** Read one current scalar sample; null remains null, rather than an invented zero. */
    Sample get(int property) throws Exception {
        return call(properties, PROPERTY, 4, object("propertyId", property, "areaId", 0),
                p -> { p.writeInt(property); p.writeInt(0); }, p -> p.readInt() == 0 ? null : Sample.read(p));
    }
    /** Subscribe at rate zero to on-change properties, generating no periodic getProperty requests. */
    void subscribe() throws Exception {
        lightSubscribed = true; register(LIGHT, true);
        dutySubscribed = true; register(DUTY, true);
    }
    /** Install or remove one property callback through the inspected interface transaction IDs. */
    private void register(int property, boolean add) throws Exception {
        call(properties, PROPERTY, add ? 1 : 2, object("propertyId", property, "rateHz", add ? 0 : null,
                "listener", events.toString()), p -> {
            p.writeInt(property);
            if (add) p.writeFloat(0);
            p.writeStrongBinder(events);
        }, p -> null);
    }
    /** Read the current native brightness once when connecting, not on a background polling loop. */
    int brightness() throws Exception { return call(power, POWER, 6, object(), p -> {}, Parcel::readInt); }
    /** Set only a validated visible native brightness; no display-off or vehicle-control transaction is exposed. */
    void setBrightness(int brightness) throws Exception {
        if (brightness < 10 || brightness > 100 || brightness % 10 != 0)
            throw new IllegalArgumentException("Brightness must be a ten-point step within 10..100");
        call(power, POWER, 7, object("brightness", brightness), p -> p.writeInt(brightness), p -> null);
    }
    /** Remove installed callbacks and binder death handling when disabled, asleep, or disconnected. */
    void close() {
        if (properties != null && properties.isBinderAlive()) {
            if (lightSubscribed) try { register(LIGHT, false); } catch (Exception ignored) { /* Already logged by call. */ }
            if (dutySubscribed) try { register(DUTY, false); } catch (Exception ignored) { /* Already logged by call. */ }
        }
        if (root != null) try { root.unlinkToDeath(death, 0); } catch (RuntimeException ignored) { /* Server already gone. */ }
        lightSubscribed = false; dutySubscribed = false; properties = null; power = null; root = null;
    }
    /** Write parameters into an obtained request parcel. */
    private interface Writer { void write(Parcel parcel) throws Exception; }
    /** Read a typed result after the server exception header has been checked. */
    private interface Reader<T> { T read(Parcel parcel) throws Exception; }
    /** Perform and faithfully JSON-log one outgoing IPC in finally, recycling both parcels in all outcomes. */
    private static <T> T call(IBinder binder, String descriptor, int transaction, JSONObject request,
            Writer writer, Reader<T> reader) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        T result = null;
        Throwable failure = null;
        try {
            if (binder == null) throw new IllegalStateException("Vehicle interface is disconnected");
            data.writeInterfaceToken(descriptor); writer.write(data);
            if (!binder.transact(transaction, data, reply, 0)) throw new RemoteException("Unimplemented transaction " + transaction);
            reply.readException(); result = reader.read(reply); return result;
        } catch (Exception error) { failure = error; throw error; }
        finally {
            Object response = result instanceof Sample ? ((Sample) result).json()
                    : result instanceof IBinder ? result.toString() : result;
            log(descriptor + "#" + transaction, request, response, failure);
            reply.recycle(); data.recycle();
        }
    }
    /** Build tiny structured diagnostics without exposing files, device identifiers or network endpoints. */
    static JSONObject object(Object... pairs) {
        JSONObject json = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) json.put((String) pairs[i], pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]); }
        catch (Exception impossible) { throw new IllegalArgumentException(impossible); }
        return json;
    }
    /** Emit exact request/result/error JSON to the system log only; sensor callbacks are not logged per event. */
    private static void log(String operation, JSONObject request, Object response, Throwable error) {
        Log.i("AutoBrightnessRpc", object("operation", operation, "request", request, "response", response,
                "error", error == null ? null : error.toString()).toString());
    }
}
