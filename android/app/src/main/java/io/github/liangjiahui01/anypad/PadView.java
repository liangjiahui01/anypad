package io.github.liangjiahui01.anypad;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The whole pad is a trackpad, made for use without looking at it:
 * one finger moves the pointer, a tap clicks, press and hold then drag selects or drags,
 * two fingers anywhere scroll. Gestures differ by finger count, never by where they start.
 */
final class PadView extends View {
    interface Host {
        void haptic(int kind);

        /** Three fingers tapped together. */
        void onThreeFingerTap();
    }

    static final int TAP = 0, HOLD = 1;

    private static final int IDLE = 0, POINT = 1, HELD = 2, SCROLL = 3, DONE = 4, TRIPLE = 5;
    private static final long TAP_MS = 250, HOLD_MS = 450, TRIPLE_MS = 500;
    // Finger pixels per wheel detent at 1.0x. With high-resolution wheels every 1/16 detent moves the page,
    // so the same gesture scrolls further than with whole detents; this keeps 1.0x moderate.
    private static final float WHEEL_PX = 150;
    private static final float DRAG_GAIN = 1.4f;     // drag scrolling: tablet counts per finger pixel
    private static final float FRICTION_TAU = 0.35f; // seconds for wheel inertia to fall to 1/e
    private static final float MIN_FLING = 150;      // px/s below which inertia stops

    private final Hid hid;
    private final Host host;
    private final float slop, edge;

    private int mode = IDLE;
    private float downX, downY, lastX, lastY;
    private long downAt;
    private boolean moved;
    private int maxPointers;
    private final android.util.SparseArray<float[]> downs = new android.util.SparseArray<>(); // pointer id -> where it landed
    private VelocityTracker vt;
    private float scrollStartX, scrollStartY;
    private boolean dragging;
    private boolean wheelMode;
    private boolean oneFinger; // one finger scrolls (reading mode)
    private float sensitivity, scrollSpeed;

    PadView(Context c, Hid hid, Host host) {
        super(c);
        this.hid = hid;
        this.host = host;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        edge = 12 * c.getResources().getDisplayMetrics().density;
        setHapticFeedbackEnabled(false);
    }

    private final Runnable holdStart = this::startHold;

    private void startHold() {
        if (mode == POINT && !moved) {
            mode = HELD;
            hid.press(Hid.LEFT);
            host.haptic(HOLD);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                stopFling();
                float x = e.getX(), y = e.getY();
                // A thumb or palm resting on the long edges while holding the phone is not a gesture
                if (x < edge || x > getWidth() - edge) { mode = DONE; return true; }
                sensitivity = Prefs.sensitivity(getContext());
                scrollSpeed = Prefs.scrollSpeed(getContext());
                wheelMode = Prefs.SCROLL_WHEEL.equals(Prefs.scrollMode(getContext()));
                oneFinger = Prefs.oneFingerScroll(getContext());
                mode = POINT;
                downX = lastX = x;
                downY = lastY = y;
                downAt = e.getEventTime();
                moved = false;
                maxPointers = 1;
                downs.clear();
                downs.put(e.getPointerId(0), new float[]{x, y});
                // Reading mode has no hold-to-select: a resting finger must not start a selection
                if (!oneFinger) postDelayed(holdStart, HOLD_MS);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                maxPointers = Math.max(maxPointers, e.getPointerCount());
                removeCallbacks(holdStart);
                int i = e.getActionIndex();
                downs.put(e.getPointerId(i), new float[]{e.getX(i), e.getY(i)});
                if (mode == POINT && e.getPointerCount() == 2) startScroll(e);
                else if (mode == SCROLL && e.getPointerCount() == 3 && !dragging) {
                    // Three fingers that landed together and have not scrolled: maybe a three-finger tap
                    endScroll(false);
                    mode = TRIPLE;
                } else if (mode == SCROLL || mode == TRIPLE) { // a scroll that grew a finger, or a fourth finger
                    endScroll(false);
                    mode = DONE;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (mode == POINT || mode == HELD) point(e);
                else if (mode == SCROLL) scrollMove(e);
                else if (mode == TRIPLE && fingersMoved(e)) mode = DONE;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == TRIPLE) return true; // fingers lifting one by one ends the tap; judged on the last
                if (mode == SCROLL) {
                    endScroll(true);
                    mode = DONE;
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(holdStart);
                if (mode == POINT && !moved && maxPointers == 1 && e.getEventTime() - downAt < TAP_MS) {
                    hid.click(Hid.LEFT);
                    host.haptic(TAP);
                } else if (mode == TRIPLE) {
                    if (e.getEventTime() - downAt < TRIPLE_MS && !fingersMoved(e)) {
                        host.onThreeFingerTap();
                        host.haptic(TAP);
                    }
                } else if (mode == HELD) {
                    hid.release(Hid.LEFT);
                } else if (mode == SCROLL) {
                    endScroll(true);
                }
                mode = IDLE;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(holdStart);
                if (mode == HELD) hid.release(Hid.LEFT);
                if (mode == SCROLL) endScroll(false);
                mode = IDLE;
                return true;
        }
        return true;
    }

    private void point(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        if (!moved) {
            if (Math.hypot(x - downX, y - downY) < slop) return;
            moved = true;
            removeCallbacks(holdStart);
            if (oneFinger && mode == POINT) {
                startScroll(e);
                scrollMove(e); // past the slop already: starts scrolling from here
                return;
            }
            lastX = x;
            lastY = y;
            return;
        }
        hid.move((x - lastX) * sensitivity, (y - lastY) * sensitivity);
        lastX = x;
        lastY = y;
    }

    private boolean fingersMoved(MotionEvent e) {
        for (int i = 0; i < e.getPointerCount(); i++) {
            float[] d = downs.get(e.getPointerId(i));
            if (d != null && Math.hypot(e.getX(i) - d[0], e.getY(i) - d[1]) > slop) return true;
        }
        return false;
    }

    // ---- two-finger scrolling -------------------------------------------------------------------------------------

    private void startScroll(MotionEvent e) {
        mode = SCROLL;
        dragging = false;
        lastX = scrollStartX = midX(e);
        lastY = scrollStartY = midY(e);
        if (vt != null) vt.recycle();
        vt = VelocityTracker.obtain();
        addMid(e);
    }

    private void scrollMove(MotionEvent e) {
        if (e.getPointerCount() < 2 && !oneFinger) return;
        float x = midX(e), y = midY(e);
        addMid(e);
        float dx = x - lastX, dy = y - lastY;
        if (!dragging) {
            if (e.getPointerCount() > 1 && Math.hypot(x - scrollStartX, y - scrollStartY) < slop) return;
            dragging = true;
            dx = dy = 0; // start from here, so the content does not jump by the slop
            if (!wheelMode) {
                hid.record();
                hid.press(Hid.LEFT);
            }
        }
        lastX = x;
        lastY = y;
        if (wheelMode) wheel(dx, dy);
        else hid.move(dx * DRAG_GAIN * scrollSpeed, dy * DRAG_GAIN * scrollSpeed);
    }

    /** Fingers up = content up, as on a touch screen. */
    private void wheel(float dx, float dy) {
        float step = WHEEL_PX / scrollSpeed;
        hid.scroll(dy / step, -dx / step);
    }

    private void endScroll(boolean fling) {
        if (dragging && !wheelMode) {
            // The release ends the swipe; the tablet flings by itself. Then put the pointer back.
            hid.release(Hid.LEFT);
            hid.rewind();
        } else if (dragging && fling && vt != null) {
            vt.computeCurrentVelocity(1000);
            startFling(vt.getXVelocity(), vt.getYVelocity());
        }
        dragging = false;
        if (vt != null) { vt.recycle(); vt = null; }
    }

    // Two-finger samples go to the tracker as one pointer at the fingers' midpoint
    private void addMid(MotionEvent e) {
        MotionEvent m = MotionEvent.obtain(e.getDownTime(), e.getEventTime(), MotionEvent.ACTION_MOVE, midX(e), midY(e), 0);
        vt.addMovement(m);
        m.recycle();
    }

    private static float midX(MotionEvent e) {
        float s = 0;
        int n = Math.min(2, e.getPointerCount());
        for (int i = 0; i < n; i++) s += e.getX(i);
        return s / n;
    }

    private static float midY(MotionEvent e) {
        float s = 0;
        int n = Math.min(2, e.getPointerCount());
        for (int i = 0; i < n; i++) s += e.getY(i);
        return s / n;
    }

    // ---- wheel inertia --------------------------------------------------------------------------------------------

    private float vx, vy;
    private long flingLast;
    private boolean flinging;

    private void startFling(float vx, float vy) {
        if (Math.hypot(vx, vy) < MIN_FLING) return;
        this.vx = vx;
        this.vy = vy;
        flingLast = System.nanoTime();
        flinging = true;
        postOnAnimation(flingStep);
    }

    private void stopFling() {
        flinging = false;
        removeCallbacks(flingStep);
    }

    private final Runnable flingStep = new Runnable() {
        @Override public void run() {
            if (!flinging) return;
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - flingLast) / 1e9f);
            flingLast = now;
            wheel(vx * dt, vy * dt);
            float k = (float) Math.exp(-dt / FRICTION_TAU);
            vx *= k;
            vy *= k;
            if (Math.hypot(vx, vy) < MIN_FLING) { flinging = false; return; }
            postOnAnimation(this);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        stopFling();
        super.onDetachedFromWindow();
    }
}
