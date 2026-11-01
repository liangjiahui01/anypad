package io.github.liangjiahui01.anypad;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SuppressLint("MissingPermission")
public final class MainActivity extends Activity implements Hid.Listener, PadView.Host {
    private static final int REQ_PERMS = 1;
    private static final int RIGHT_BAR_DP = 56;
    private static final float SPEED_MIN = 0.1f;
    private static final int SPEED_STEPS = 29; // 0.1x .. 3.0x in tenths

    private Hid hid;
    private TextView modePointer, modeScroll;
    private View pointerRow, scrollRow;
    private View pad, rightBar, padLabel;
    private GradientDrawable padBg;
    private TextView chip, hint, padTitle, padSub, error;
    private Vibrator vibrator;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        hid = Hid.get(this);
        vibrator = getSystemService(Vibrator.class);
        Window w = getWindow();
        w.setStatusBarColor(Palette.BAR);
        w.setNavigationBarColor(Palette.BG);
        setContentView(build());
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);

        // Top bar: name, connection chip, settings
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Palette.BAR);
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));
        // Pointer | Scroll: what one finger does
        LinearLayout modes = new LinearLayout(this);
        GradientDrawable modesBg = new GradientDrawable();
        modesBg.setColor(Palette.KEY);
        modesBg.setCornerRadius(dp(20));
        modes.setBackground(modesBg);
        modes.setPadding(dp(3), dp(3), dp(3), dp(3));
        modePointer = modeChip(R.string.mode_pointer, false);
        modeScroll = modeChip(R.string.mode_scroll, true);
        modes.addView(modePointer);
        modes.addView(modeScroll);
        LinearLayout left = new LinearLayout(this);
        left.addView(modes);
        bar.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        chip = Ui.text(this, "", 14, Palette.TEXT, false);
        chip.setSingleLine();
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth(dp(170));
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setOnClickListener(v -> showDevices());
        bar.addView(chip);
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setColorFilter(Palette.SUB);
        gear.setPadding(dp(10), dp(10), dp(10), dp(10));
        gear.setBackground(Ui.ripple());
        gear.setContentDescription(getString(R.string.settings));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(gear, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(bar);

        // Speed sliders, to tune while trying it out: pointer speed (Pointer mode only) and scroll speed
        pointerRow = speedRow(R.string.sensitivity, () -> Prefs.sensitivity(this), v -> Prefs.sensitivity(this, v));
        scrollRow = speedRow(R.string.scroll_speed, () -> Prefs.scrollSpeed(this), v -> Prefs.scrollSpeed(this, v));
        root.addView(pointerRow);
        root.addView(scrollRow);

        error = Ui.text(this, "", 14, Palette.AMBER, false);
        error.setPadding(dp(16), dp(10), dp(16), dp(10));
        error.setBackgroundColor(Palette.AMBER_BG);
        error.setVisibility(View.GONE);
        root.addView(error);

        // The pad, with a faint label in the middle and the key hints along the bottom
        FrameLayout padBox = new FrameLayout(this);
        padBg = new GradientDrawable();
        padBg.setColor(Palette.PAD);
        padBg.setCornerRadius(dp(20));
        padBox.setBackground(padBg);
        pad = new PadView(this, hid, this);
        pad.setContentDescription(getString(R.string.pad_desc));
        FrameLayout.LayoutParams padLpInner = new FrameLayout.LayoutParams(-1, -1);
        padLpInner.rightMargin = dp(RIGHT_BAR_DP);
        padBox.addView(pad, padLpInner);
        rightBar = scrollStrip();
        padBox.addView(rightBar, new FrameLayout.LayoutParams(dp(RIGHT_BAR_DP), -1, Gravity.END));

        LinearLayout label = new LinearLayout(this);
        label.setOrientation(LinearLayout.VERTICAL);
        label.setGravity(Gravity.CENTER);
        padTitle = Ui.text(this, "", 17, Palette.ACCENT, true);
        padTitle.setGravity(Gravity.CENTER);
        padSub = Ui.text(this, getString(R.string.pad_gestures), 13, Palette.FAINT, false);
        padSub.setGravity(Gravity.CENTER);
        padSub.setLineSpacing(0, 1.3f);
        padSub.setPadding(dp(24), dp(10), dp(24), 0);
        label.addView(padTitle);
        label.addView(padSub);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        labelLp.rightMargin = dp(RIGHT_BAR_DP); // centred on the trackpad, clear of the scroll strip
        padBox.addView(label, labelLp);
        padLabel = label;

        hint = Ui.text(this, "", 13, Palette.SUB, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), 0, dp(16), dp(18));
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hintLp.rightMargin = dp(RIGHT_BAR_DP);
        padBox.addView(hint, hintLp);

        LinearLayout.LayoutParams padLp = new LinearLayout.LayoutParams(-1, 0, 1);
        padLp.setMargins(dp(12), dp(12), dp(12), dp(10));
        root.addView(padBox, padLp);

        // Android's three navigation keys, as on older phones: Back, Home, Recents
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(dp(12), 0, dp(12), dp(12));
        buttons.addView(navButton(R.drawable.ic_nav_back, R.string.act_back, Action.BACK), weight());
        buttons.addView(space());
        buttons.addView(navButton(R.drawable.ic_nav_home, R.string.act_home, Action.HOME), weight());
        buttons.addView(space());
        buttons.addView(navButton(R.drawable.ic_nav_recents, R.string.act_recents, Action.RECENTS), weight());
        root.addView(buttons);
        return root;
    }

    /**
     * A tall strip along the pad's right side for reading: hold the upper half to scroll up, the lower half to
     * scroll down, at an even speed that picks up after a second; a tap nudges a few lines. Smooth because the
     * wheel is high-resolution and the scroll goes out a little every frame.
     */
    @SuppressLint("ClickableViewAccessibility")
    private View scrollStrip() {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.KEY);
        bg.setCornerRadii(new float[]{0, 0, dp(20), dp(20), dp(20), dp(20), 0, 0});
        strip.setBackground(bg);
        TextView up = Ui.text(this, "▲", 14, Palette.FAINT, false);
        TextView down = Ui.text(this, "▼", 14, Palette.FAINT, false);
        up.setGravity(Gravity.CENTER);
        down.setGravity(Gravity.CENTER);
        up.setContentDescription(getString(R.string.hold_up));
        down.setContentDescription(getString(R.string.hold_down));
        View line = new View(this);
        line.setBackgroundColor(Palette.LINE);
        strip.addView(up, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, dp(1));
        lineLp.setMargins(dp(12), 0, dp(12), 0);
        strip.addView(line, lineLp);
        strip.addView(down, new LinearLayout.LayoutParams(-1, 0, 1));
        strip.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    boolean isUp = e.getY() < v.getHeight() / 2f;
                    (isUp ? up : down).setTextColor(Palette.ACCENT);
                    if (hid.state() == Hid.CONNECTED) {
                        haptic(PadView.TAP);
                        holdScroll.start(isUp ? 1 : -1);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    up.setTextColor(Palette.FAINT);
                    down.setTextColor(Palette.FAINT);
                    holdScroll.stop();
                    return true;
            }
            return true;
        });
        return strip;
    }

    /** The scrolling behind the strip: runs every frame while held. */
    private final class HoldScroll implements Runnable {
        // Its own speed, apart from the finger scroll speed (which is usually turned well down)
        private static final float BASE = 3f;          // detents a second while held
        private static final float RAMP_AFTER = 1f;    // seconds at reading speed before it picks up
        private static final float RAMP_TO = 4f;       // ...up to this many times faster
        private static final float TAP_S = 0.25f, NUDGE = 1.5f; // a tap scrolls this many detents in all

        private int dir;
        private long start, last;
        private float done; // detents sent since the press
        private boolean held, running;

        void start(int dir) {
            this.dir = dir;
            start = last = System.nanoTime();
            done = 0;
            held = true;
            if (!running) { running = true; chip.postOnAnimation(this); }
        }

        void stop() {
            held = false; // a short tap keeps going until it has nudged NUDGE detents
        }

        @Override public void run() {
            long now = System.nanoTime();
            float t = (now - start) / 1e9f, dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            float rate = BASE;
            if (t > RAMP_AFTER) rate *= Math.min(RAMP_TO, 1 + (t - RAMP_AFTER) * 1.5f);
            float step;
            if (held) {
                step = rate * dt;
            } else {
                float nudge = NUDGE;
                if (t > TAP_S || done >= nudge) { running = false; return; }
                step = Math.min(nudge - done, rate * 3 * dt);
            }
            done += step;
            hid.scroll(dir * step, 0);
            chip.postOnAnimation(this);
        }
    }

    private final HoldScroll holdScroll = new HoldScroll();

    private View navButton(int icon, int label, Action action) {
        ImageView v = new ImageView(this);
        v.setImageResource(icon);
        v.setColorFilter(Palette.TEXT);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(getString(label));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Palette.KEY);
        bg.setCornerRadius(dp(12));
        v.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33cdd6f4), bg, null));
        v.setOnClickListener(x -> {
            if (hid.state() != Hid.CONNECTED) return;
            action.send(hid);
            haptic(PadView.TAP);
        });
        return v;
    }

    interface FloatGet { float get(); }

    interface FloatSet { void set(float v); }

    /** A labelled 0.4x..3.0x slider; its tag is a Runnable that reloads the saved value. */
    private View speedRow(int label, FloatGet get, FloatSet set) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Palette.BAR);
        row.setPadding(dp(16), 0, dp(16), dp(2));
        TextView name = Ui.text(this, getString(label), 13, Palette.SUB, false);
        name.setMinWidth(dp(96));
        row.addView(name);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(SPEED_STEPS);
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(Palette.ACCENT);
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        TextView value = Ui.text(this, "", 13, Palette.TEXT, false);
        value.setMinWidth(dp(40));
        value.setGravity(Gravity.END);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean user) {
                float v = SPEED_MIN + p / 10f;
                value.setText(String.format(java.util.Locale.ROOT, "%.1f×", v));
                if (user) set.set(v);
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }

            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, dp(40), 1));
        row.addView(value);
        row.setTag((Runnable) () -> {
            float v = get.get();
            bar.setProgress(Math.round((v - SPEED_MIN) * 10));
            value.setText(String.format(java.util.Locale.ROOT, "%.1f×", v));
        });
        return row;
    }

    private TextView modeChip(int label, boolean scroll) {
        TextView t = Ui.text(this, getString(label), 14, Palette.SUB, false);
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        t.setOnClickListener(v -> {
            Prefs.oneFingerScroll(this, scroll);
            showMode();
            haptic(PadView.TAP);
        });
        return t;
    }

    /** Top-bar switch, pad colour and gesture help follow the one-finger mode. */
    private void showMode() {
        boolean scroll = Prefs.oneFingerScroll(this);
        for (TextView t : new TextView[]{modePointer, modeScroll}) {
            boolean on = (t == modeScroll) == scroll;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(17));
            bg.setColor(on ? Palette.ACCENT : 0);
            t.setBackground(bg);
            t.setTextColor(on ? Palette.BG : Palette.SUB);
        }
        padBg.setColor(scroll ? Palette.PAD_SCROLL : Palette.PAD);
        padSub.setText(scroll ? R.string.pad_gestures_scroll : R.string.pad_gestures);

        pointerRow.setVisibility(scroll ? View.GONE : View.VISIBLE);
        ((Runnable) pointerRow.getTag()).run();
        ((Runnable) scrollRow.getTag()).run();

        // The hold-to-scroll strip is for Pointer mode; in Scroll mode the pad takes the full width
        rightBar.setVisibility(scroll ? View.GONE : View.VISIBLE);
        int margin = scroll ? 0 : dp(RIGHT_BAR_DP);
        for (View w : new View[]{pad, padLabel, hint}) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) w.getLayoutParams();
            lp.rightMargin = margin;
            w.setLayoutParams(lp);
        }
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(48), 1);
    }

    private View space() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        return v;
    }

    // ---- lifecycle and Bluetooth -------------------------------------------------------------------------------------

    @Override
    protected void onStart() {
        super.onStart();
        hid.addListener(this);
        if (hasPerms()) hid.start();
        else requestPermissions(perms(), REQ_PERMS);
        onHidChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHint(); // the mapping may have changed in settings
        showMode();
        hid.keepAwake(true);
        hid.sync(); // the connection may have changed while away
    }

    @Override
    protected void onPause() {
        hid.keepAwake(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        hid.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Leaving the app hands the phone's Bluetooth device role back (to other apps like it)
        if (isFinishing()) hid.stop();
        super.onDestroy();
    }

    private static String[] perms() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE}
            : new String[0];
    }

    private boolean hasPerms() {
        for (String p : perms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (hasPerms()) hid.start();
        else showError(getString(R.string.err_perm));
    }

    @Override
    public void onHidChanged() {
        BluetoothDevice d = hid.device();
        String dn = d == null ? null : deviceName(d);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        switch (hid.state()) {
            case Hid.CONNECTED:
                chip.setText("● " + dn);
                chip.setTextColor(Palette.OK);
                bg.setColor(Palette.OK_BG);
                padTitle.setText(dn);
                padTitle.setTextColor(Palette.ACCENT);
                break;
            case Hid.CONNECTING:
                chip.setText(getString(R.string.connecting, dn));
                chip.setTextColor(Palette.AMBER);
                bg.setColor(Palette.AMBER_BG);
                padTitle.setText(getString(R.string.connecting, dn));
                padTitle.setTextColor(Palette.AMBER);
                break;
            default:
                chip.setText(R.string.choose_device);
                chip.setTextColor(Palette.TEXT);
                bg.setColor(Palette.KEY);
                padTitle.setText(R.string.not_connected);
                padTitle.setTextColor(Palette.FAINT);
        }
        chip.setBackground(bg);
        String err = hid.error();
        if (err != null) showError(err);
        else error.setVisibility(View.GONE);
        screenState();
    }

    private void showError(String s) {
        error.setText(s);
        error.setVisibility(View.VISIBLE);
    }

    static String deviceName(BluetoothDevice d) {
        String n = null;
        try { n = d.getName(); } catch (SecurityException ignored) { }
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    private void showDevices() {
        BluetoothAdapter a = hid.adapter();
        if (a == null || !hasPerms()) { requestPermissions(perms(), REQ_PERMS); return; }
        Set<BluetoothDevice> bonded = a.getBondedDevices();
        List<BluetoothDevice> list = new ArrayList<>();
        String last = Prefs.lastDevice(this);
        for (BluetoothDevice d : bonded) {
            // Headphones and watches cannot use a mouse; show what has a screen
            int major = d.getBluetoothClass() == null ? -1 : d.getBluetoothClass().getMajorDeviceClass();
            if (major == BluetoothClass.Device.Major.AUDIO_VIDEO || major == BluetoothClass.Device.Major.WEARABLE
                || major == BluetoothClass.Device.Major.HEALTH || major == BluetoothClass.Device.Major.PERIPHERAL) continue;
            if (d.getAddress().equals(last)) list.add(0, d);
            else list.add(d);
        }
        List<String> names = new ArrayList<>();
        for (BluetoothDevice d : list) names.add(deviceName(d));
        names.add(getString(R.string.pair_new));
        AlertDialog.Builder bd = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.choose_device)
            .setItems(names.toArray(new String[0]), (dlg, i) -> {
                if (i < list.size()) hid.connect(list.get(i));
                else pairNew();
            });
        if (hid.state() != Hid.OFF) bd.setNegativeButton(R.string.disconnect, (dlg, i) -> hid.disconnect());
        bd.show();
    }

    private void pairNew() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.pair_new)
            .setMessage(getString(R.string.pair_steps, hid.adapter().getName()))
            .setPositiveButton(R.string.pair_go, (d, i) -> {
                Intent it = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                it.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
                startActivity(it);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---- phone keys ------------------------------------------------------------------------------------------------

    private Prefs.Key shortKey(int code) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP) return Prefs.Key.VOL_UP_SHORT;
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) return Prefs.Key.VOL_DOWN_SHORT;
        return null;
    }

    private Prefs.Key longKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP ? Prefs.Key.VOL_UP_LONG : Prefs.Key.VOL_DOWN_LONG;
    }

    private boolean longFired;

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (shortKey(code) == null || hid.state() != Hid.CONNECTED) return super.onKeyDown(code, e);
        if (e.getRepeatCount() == 0) {
            longFired = false;
            // Long presses are only told apart when one is mapped. Without one, act on the press itself:
            // no waiting for the release.
            if (Prefs.action(this, longKey(code)) != Action.NONE) e.startTracking();
            else {
                longFired = true; // nothing left to do on release
                fire(shortKey(code));
            }
        }
        return true;
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (shortKey(code) == null || hid.state() != Hid.CONNECTED) return super.onKeyLongPress(code, e);
        longFired = true;
        Prefs.action(this, longKey(code)).send(hid);
        haptic(PadView.HOLD);
        return true;
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        Prefs.Key k = shortKey(code);
        if (k == null || hid.state() != Hid.CONNECTED) return super.onKeyUp(code, e);
        if (!longFired && !e.isCanceled()) fire(k);
        longFired = false;
        return true;
    }

    private void fire(Prefs.Key k) {
        Action a = Prefs.action(this, k);
        a.send(hid);
        if (a != Action.NONE) haptic(PadView.TAP);
    }

    private void updateHint() {
        StringBuilder s = new StringBuilder();
        for (Prefs.Key k : Prefs.Key.values()) {
            Action a = Prefs.action(this, k);
            if (a == Action.NONE) continue;
            if (s.length() > 0) s.append("  ·  ");
            String key = getString(k.longPress ? R.string.hint_long : R.string.hint_short, getString(k.label));
            s.append(getString(R.string.hint_pair, key, getString(a.label)));
        }
        if (s.length() > 0) s.append("  ·  ");
        s.append(getString(R.string.hint_three_back));
        hint.setText(s);
    }

    // ---- screen: stays on while connected, so the volume keys keep working --------------------------------------

    private void screenState() {
        if (hid.state() == Hid.CONNECTED) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onThreeFingerTap() {
        if (hid.state() == Hid.CONNECTED) Action.BACK.send(hid);
    }

    // ---- feedback --------------------------------------------------------------------------------------------------

    @Override
    public void haptic(int kind) {
        if (vibrator == null || !Prefs.haptics(this)) return;
        vibrator.vibrate(VibrationEffect.createPredefined(
            kind == PadView.HOLD ? VibrationEffect.EFFECT_HEAVY_CLICK : VibrationEffect.EFFECT_CLICK));
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
