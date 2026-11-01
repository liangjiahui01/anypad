package io.github.liangjiahui01.anypad;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.TextView;

/** Small view builders; all UI is built in code. */
final class Ui {
    private Ui() {}

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static Drawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x33cdd6f4), null, null);
    }
}
