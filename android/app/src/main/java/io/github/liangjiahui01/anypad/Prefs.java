package io.github.liangjiahui01.anypad;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings, in one SharedPreferences file. */
final class Prefs {
    private Prefs() {}

    /** The phone's keys that can be mapped, each with a short and a long press. */
    enum Key {
        VOL_UP_SHORT("vol_up_short", R.string.key_vol_up, false, Action.PLAY_PAUSE),
        VOL_UP_LONG("vol_up_long", R.string.key_vol_up, true, Action.HOME),
        VOL_DOWN_SHORT("vol_down_short", R.string.key_vol_down, false, Action.BACK),
        VOL_DOWN_LONG("vol_down_long", R.string.key_vol_down, true, Action.NONE);

        final String pref;
        final int label;
        final boolean longPress;
        final Action fallback;

        Key(String pref, int label, boolean longPress, Action fallback) {
            this.pref = pref;
            this.label = label;
            this.longPress = longPress;
            this.fallback = fallback;
        }
    }

    static final String SCROLL_DRAG = "drag", SCROLL_WHEEL = "wheel";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("anypad", Context.MODE_PRIVATE);
    }

    static Action action(Context c, Key k) {
        return Action.parse(sp(c).getString("map_" + k.pref, null), k.fallback);
    }

    static void action(Context c, Key k, Action a) {
        sp(c).edit().putString("map_" + k.pref, a.name()).apply();
    }

    static String lastDevice(Context c) {
        return sp(c).getString("last_device", null);
    }

    static void lastDevice(Context c, String address) {
        sp(c).edit().putString("last_device", address).apply();
    }

    /** Pointer speed: phone pixels times this. */
    static float sensitivity(Context c) { return sp(c).getFloat("sensitivity", 1.2f); }

    static void sensitivity(Context c, float v) { sp(c).edit().putFloat("sensitivity", v).apply(); }

    /** How far two fingers scroll, relative to the default. */
    static float scrollSpeed(Context c) { return sp(c).getFloat("scroll_speed", 1f); }

    static void scrollSpeed(Context c, float v) { sp(c).edit().putFloat("scroll_speed", v).apply(); }

    /** "drag": two fingers press and drag, like a finger on the screen. "wheel": a mouse wheel. */
    static String scrollMode(Context c) { return sp(c).getString("scroll_mode", SCROLL_DRAG); }

    static void scrollMode(Context c, String v) { sp(c).edit().putString("scroll_mode", v).apply(); }

    static boolean haptics(Context c) { return sp(c).getBoolean("haptics", true); }

    static void haptics(Context c, boolean v) { sp(c).edit().putBoolean("haptics", v).apply(); }

    /** Dim the phone's screen while connected (it stays on, so the volume keys keep working). */
    static boolean dim(Context c) { return sp(c).getBoolean("dim", true); }

    static void dim(Context c, boolean v) { sp(c).edit().putBoolean("dim", v).apply(); }

    /** Back to the defaults for everything but the last device. */
    static void reset(Context c) {
        String last = lastDevice(c);
        sp(c).edit().clear().putString("last_device", last).apply();
    }
}
