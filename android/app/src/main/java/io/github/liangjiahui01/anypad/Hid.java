package io.github.liangjiahui01.anypad;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The phone as a Bluetooth mouse, keyboard and media remote (one HID device with three reports).
 * The other side needs nothing installed: it sees an ordinary Bluetooth mouse and keyboard.
 * All Bluetooth calls run on one background thread, in the order they were made.
 */
@SuppressLint("MissingPermission")
final class Hid {
    interface Listener {
        void onHidChanged();
    }

    static final int OFF = 0, CONNECTING = 1, CONNECTED = 2;
    static final int LEFT = 1, RIGHT = 2, MIDDLE = 4;

    private static final byte ID_MOUSE = 1, ID_KEYBOARD = 2, ID_CONSUMER = 3;

    // Report 1: 5 buttons, 16-bit relative X/Y, wheel, horizontal pan (AC Pan).
    // Report 2: a boot-style keyboard (modifiers + 6 keys).
    // Report 3: one 16-bit consumer usage (Back, Home, play/pause, volume...).
    private static final byte[] DESCRIPTOR = bytes(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, ID_MOUSE,
        0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,

        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, ID_KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,

        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, ID_CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0);

    private static final long FRAME_MS = 8; // at most ~120 mouse reports a second
    private static final long RETRY_MS = 5000;

    private static Hid instance;

    static synchronized Hid get(Context c) {
        if (instance == null) instance = new Hid(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler io;
    private final Executor ioExec;
    private final List<Listener> listeners = new ArrayList<>();

    // Read and written on the main thread
    private volatile BluetoothHidDevice proxy;
    private boolean starting, registered, wantOn;
    private volatile BluetoothDevice device;
    private volatile int state = OFF;
    private String error;

    // Mouse state, on the io thread only
    private int buttons;
    private float accX, accY, accWheel, accPan;
    private boolean flushQueued;
    private long lastSent;
    private boolean recording;
    private final ArrayList<long[]> recorded = new ArrayList<>(); // {dx, dy, gap ms}

    private Hid(Context app) {
        this.app = app;
        HandlerThread t = new HandlerThread("hid");
        t.start();
        io = new Handler(t.getLooper());
        ioExec = io::post;
    }

    // ---- connection --------------------------------------------------------------------------------------------

    int state() { return state; }

    BluetoothDevice device() { return device; }

    String error() { return error; }

    boolean registered() { return registered; }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        for (Listener l : new ArrayList<>(listeners)) l.onHidChanged();
    }

    BluetoothAdapter adapter() {
        BluetoothManager m = app.getSystemService(BluetoothManager.class);
        return m == null ? null : m.getAdapter();
    }

    /** Registers the HID app and connects to the last device. Safe to call again. */
    void start() {
        wantOn = true;
        BluetoothAdapter a = adapter();
        if (a == null) { fail(app.getString(R.string.err_no_bt)); return; }
        if (!a.isEnabled()) { fail(app.getString(R.string.err_bt_off)); return; }
        if (proxy != null) {
            if (!registered) register();
            return;
        }
        if (starting) return;
        starting = true;
        boolean ok = a.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile p) {
                starting = false;
                proxy = (BluetoothHidDevice) p;
                if (wantOn) register();
            }

            @Override public void onServiceDisconnected(int profile) {
                proxy = null;
                registered = false;
                setState(null, OFF);
            }
        }, BluetoothProfile.HID_DEVICE);
        if (!ok) {
            starting = false;
            fail(app.getString(R.string.err_no_hid));
        }
    }

    /** Lets the phone go: other apps may use it as a Bluetooth device again. */
    void stop() {
        wantOn = false;
        main.removeCallbacks(retry);
        if (proxy != null) {
            if (registered) proxy.unregisterApp();
            adapter().closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
        }
        proxy = null;
        registered = false;
        setState(null, OFF);
    }

    private void register() {
        BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
            "Anypad", "Phone as a trackpad and remote", "Anypad", BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR);
        boolean ok;
        try {
            ok = proxy.registerApp(sdp, null, null, ioExec, callback);
        } catch (SecurityException e) {
            ok = false;
        }
        if (!ok) fail(app.getString(R.string.err_register));
    }

    private void fail(String why) {
        error = why;
        changed();
    }

    /** Connects to a paired device (the tablet). */
    void connect(BluetoothDevice d) {
        Prefs.lastDevice(app, d.getAddress());
        if (proxy == null || !registered) { start(); return; }
        if (device != null && state != OFF && !device.equals(d)) proxy.disconnect(device);
        setState(d, CONNECTING);
        if (!proxy.connect(d)) setState(d, OFF);
    }

    void disconnect() {
        Prefs.lastDevice(app, null);
        main.removeCallbacks(retry);
        if (proxy != null && device != null) proxy.disconnect(device);
        setState(null, OFF);
    }

    private BluetoothDevice lastDevice() {
        String addr = Prefs.lastDevice(app);
        BluetoothAdapter a = adapter();
        if (addr == null || a == null || !BluetoothAdapter.checkBluetoothAddress(addr)) return null;
        return a.getRemoteDevice(addr);
    }

    // Reconnects to the last device while the app is open, every few seconds.
    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (!wantOn || proxy == null || !registered || state == CONNECTED) return;
            BluetoothDevice d = lastDevice();
            if (d == null) return;
            if (state == OFF) {
                setState(d, CONNECTING);
                if (!proxy.connect(d)) setState(d, OFF);
            }
            main.postDelayed(this, RETRY_MS);
        }
    };

    private void setState(BluetoothDevice d, int s) {
        device = d;
        state = s;
        if (s == CONNECTED) error = null;
        changed();
    }

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            main.post(() -> {
                registered = reg;
                if (reg) {
                    error = null;
                    main.removeCallbacks(retry);
                    main.post(retry);
                } else if (wantOn) {
                    error = app.getString(R.string.err_register);
                }
                changed();
            });
        }

        @Override public void onConnectionStateChanged(BluetoothDevice d, int s) {
            main.post(() -> {
                if (s == BluetoothProfile.STATE_CONNECTED) {
                    Prefs.lastDevice(app, d.getAddress());
                    setState(d, CONNECTED);
                } else if (s == BluetoothProfile.STATE_CONNECTING) {
                    setState(d, CONNECTING);
                } else if (s == BluetoothProfile.STATE_DISCONNECTED) {
                    if (device == null || device.equals(d)) {
                        setState(device, OFF);
                        io.post(() -> { buttons = 0; recording = false; });
                        main.removeCallbacks(retry);
                        main.postDelayed(retry, 1500);
                    }
                }
            });
        }

        @Override public void onGetReport(BluetoothDevice d, byte type, byte id, int size) {
            int len = id == ID_MOUSE ? 7 : id == ID_KEYBOARD ? 8 : 2;
            if (proxy != null) proxy.replyReport(d, type, id, new byte[len]);
        }

        @Override public void onSetReport(BluetoothDevice d, byte type, byte id, byte[] data) {
            if (proxy != null) proxy.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS);
        }
    };

    // ---- reports (called from the UI thread; everything queues onto the io thread in order) ----------------------

    /** Moves the pointer by a fraction of counts; fractions carry over. */
    void move(float dx, float dy) {
        io.post(() -> { accX += dx; accY += dy; queueFlush(); });
    }

    /** Scrolls by wheel detents (fractions carry over). Positive wheel = up, positive pan = right. */
    void scroll(float wheel, float pan) {
        io.post(() -> { accWheel += wheel; accPan += pan; queueFlush(); });
    }

    void press(int button) {
        io.post(() -> { flush(); buttons |= button; sendMouse(0, 0, 0, 0); });
    }

    void release(int button) {
        io.post(() -> { flush(); buttons &= ~button; sendMouse(0, 0, 0, 0); });
    }

    void click(int button) {
        press(button);
        release(button);
    }

    void releaseAll() {
        io.post(() -> { flush(); buttons = 0; sendMouse(0, 0, 0, 0); });
    }

    /** Records the movement sent from now on, so it can be undone with {@link #rewind()}. */
    void record() {
        io.post(() -> { flush(); recorded.clear(); recording = true; });
    }

    /**
     * Moves the pointer back where recording started: the same steps reversed, with the same timing,
     * so the other side's pointer acceleration takes them back the same distance.
     */
    void rewind() {
        io.post(() -> {
            flush();
            recording = false;
            long at = SystemClock.uptimeMillis();
            for (int i = recorded.size() - 1; i >= 0; i--) {
                long[] r = recorded.get(i);
                at += Math.max(r[2], FRAME_MS);
                int x = (int) -r[0], y = (int) -r[1];
                io.postAtTime(() -> sendMouse(x, y, 0, 0), at);
            }
            recorded.clear();
        });
    }

    /** One consumer-control press: Back, Home, play/pause, volume... */
    void consumer(int usage) {
        io.post(() -> {
            send(ID_CONSUMER, new byte[]{(byte) usage, (byte) (usage >> 8)});
            send(ID_CONSUMER, new byte[2]);
        });
    }

    /** One key press on the keyboard report, with modifiers (HID usage IDs). */
    void key(int modifiers, int usage) {
        io.post(() -> {
            send(ID_KEYBOARD, new byte[]{(byte) modifiers, 0, (byte) usage, 0, 0, 0, 0, 0});
            send(ID_KEYBOARD, new byte[8]);
        });
    }

    private void queueFlush() {
        if (flushQueued) return;
        flushQueued = true;
        long wait = Math.max(0, lastSent + FRAME_MS - SystemClock.uptimeMillis());
        io.postDelayed(() -> { flushQueued = false; flush(); }, wait);
    }

    private void flush() {
        int x = clamp((int) accX, 32767), y = clamp((int) accY, 32767);
        int w = clamp((int) accWheel, 127), p = clamp((int) accPan, 127);
        if (x == 0 && y == 0 && w == 0 && p == 0) return;
        accX -= x; accY -= y; accWheel -= w; accPan -= p;
        long now = SystemClock.uptimeMillis();
        if (recording && (x != 0 || y != 0)) recorded.add(new long[]{x, y, recorded.isEmpty() ? 0 : now - lastSent});
        sendMouse(x, y, w, p);
        // Whatever did not fit goes in the next report
        if ((int) accX != 0 || (int) accY != 0 || (int) accWheel != 0 || (int) accPan != 0) queueFlush();
    }

    private void sendMouse(int x, int y, int wheel, int pan) {
        lastSent = SystemClock.uptimeMillis();
        send(ID_MOUSE, new byte[]{(byte) buttons, (byte) x, (byte) (x >> 8), (byte) y, (byte) (y >> 8),
            (byte) wheel, (byte) pan});
    }

    private void send(byte id, byte[] data) {
        BluetoothHidDevice p = proxy;
        BluetoothDevice d = device;
        if (p == null || d == null || state != CONNECTED) return;
        try {
            p.sendReport(d, id, data);
        } catch (RuntimeException ignored) {
            // Disconnected between the check and the call
        }
    }

    private static int clamp(int v, int max) {
        return Math.max(-max, Math.min(max, v));
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }
}
