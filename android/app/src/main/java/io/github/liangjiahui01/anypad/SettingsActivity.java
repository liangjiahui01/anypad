package io.github.liangjiahui01.anypad;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;

/** Key mapping, pad feel, screen; one scrolling page built in code. */
public final class SettingsActivity extends Activity {
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Palette.BAR);
        getWindow().setNavigationBarColor(Palette.BG);
        render();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Palette.BAR);
        bar.setPadding(dp(4), dp(6), dp(16), dp(6));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(Palette.TEXT);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setBackground(Ui.ripple());
        back.setContentDescription(getString(R.string.back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        bar.addView(Ui.text(this, getString(R.string.settings), 18, Palette.TEXT, true));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        section(R.string.sec_keys);
        note(R.string.keys_note);
        for (Prefs.Key k : Prefs.Key.values()) {
            String title = getString(k.longPress ? R.string.hint_long : R.string.hint_short, getString(k.label));
            row(title, getString(Prefs.action(this, k).label), v -> pickAction(k));
        }

        section(R.string.sec_pad);
        slider(R.string.sensitivity, 0.4f, 3f, Prefs.sensitivity(this), v -> Prefs.sensitivity(this, v));
        String mode = Prefs.scrollMode(this);
        row(getString(R.string.scroll_mode),
            getString(Prefs.SCROLL_WHEEL.equals(mode) ? R.string.scroll_wheel : R.string.scroll_drag), v -> pickScroll());
        note(R.string.scroll_note);
        slider(R.string.scroll_speed, 0.4f, 3f, Prefs.scrollSpeed(this), v -> Prefs.scrollSpeed(this, v));
        toggle(R.string.haptics, Prefs.haptics(this), v -> Prefs.haptics(this, v));

        section(R.string.sec_screen);
        toggle(R.string.dim, Prefs.dim(this), v -> Prefs.dim(this, v));
        note(R.string.dim_note);

        section(R.string.sec_other);
        row(getString(R.string.reset), null, v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(R.string.reset_confirm)
            .setPositiveButton(R.string.reset, (d, i) -> { Prefs.reset(this); render(); })
            .setNegativeButton(android.R.string.cancel, null)
            .show());
        row(getString(R.string.version), BuildConfig.VERSION_NAME, null);

        setContentView(root);
    }

    private void pickAction(Prefs.Key k) {
        Action[] all = Action.values();
        String[] names = new String[all.length];
        int checked = 0;
        Action cur = Prefs.action(this, k);
        for (int i = 0; i < all.length; i++) {
            names[i] = getString(all[i].label);
            if (all[i] == cur) checked = i;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(k.longPress ? R.string.hint_long : R.string.hint_short, getString(k.label)))
            .setSingleChoiceItems(names, checked, (d, i) -> {
                Prefs.action(this, k, all[i]);
                d.dismiss();
                render();
            })
            .show();
    }

    private void pickScroll() {
        String[] modes = {Prefs.SCROLL_DRAG, Prefs.SCROLL_WHEEL};
        String[] names = {getString(R.string.scroll_drag), getString(R.string.scroll_wheel)};
        int checked = Prefs.SCROLL_WHEEL.equals(Prefs.scrollMode(this)) ? 1 : 0;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.scroll_mode)
            .setSingleChoiceItems(names, checked, (d, i) -> {
                Prefs.scrollMode(this, modes[i]);
                d.dismiss();
                render();
            })
            .show();
    }

    // ---- rows ------------------------------------------------------------------------------------------------------

    private void section(int title) {
        TextView t = Ui.text(this, getString(title), 13, Palette.ACCENT, true);
        t.setPadding(dp(20), dp(24), dp(20), dp(6));
        list.addView(t);
    }

    private void note(int text) {
        TextView t = Ui.text(this, getString(text), 13, Palette.FAINT, false);
        t.setPadding(dp(20), 0, dp(20), dp(8));
        t.setLineSpacing(0, 1.2f);
        list.addView(t);
    }

    private void row(String title, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        r.addView(Ui.text(this, title, 16, Palette.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) r.addView(Ui.text(this, value, 15, Palette.SUB, false));
        if (click != null) {
            r.setBackground(Ui.ripple());
            r.setOnClickListener(click);
        }
        list.addView(r);
    }

    interface FloatSink { void set(float v); }

    interface BoolSink { void set(boolean v); }

    private void slider(int title, float min, float max, float value, FloatSink sink) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));
        LinearLayout top = new LinearLayout(this);
        top.addView(Ui.text(this, getString(title), 16, Palette.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView val = Ui.text(this, fmt(value), 15, Palette.SUB, false);
        top.addView(val);
        box.addView(top);
        SeekBar sb = new SeekBar(this);
        int steps = Math.round((max - min) * 10);
        sb.setMax(steps);
        sb.setProgress(Math.round((value - min) * 10));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                float v = min + p / 10f;
                val.setText(fmt(v));
                if (user) sink.set(v);
            }

            @Override public void onStartTrackingTouch(SeekBar s) { }

            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb);
        list.addView(box);
    }

    private void toggle(int title, boolean on, BoolSink sink) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(10), dp(20), dp(10));
        r.addView(Ui.text(this, getString(title), 16, Palette.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1));
        Switch s = new Switch(this);
        s.setChecked(on);
        s.setOnCheckedChangeListener((v, c) -> sink.set(c));
        r.addView(s);
        r.setOnClickListener(v -> s.toggle());
        list.addView(r);
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.1f×", v);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
