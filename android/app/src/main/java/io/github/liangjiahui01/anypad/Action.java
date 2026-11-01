package io.github.liangjiahui01.anypad;

/**
 * What a phone key can do. Settings store the action; {@link #send} turns it into the keys the other side
 * understands. Only Android tablets and phones for now: another kind of device (Mac, Windows) gets its own
 * translation here, and the mapping screen stays the same.
 */
enum Action {
    NONE(R.string.act_none, 0),
    BACK(R.string.act_back, 0x0224),          // AC Back -> Android BACK
    HOME(R.string.act_home, 0x0223),          // AC Home -> Android HOME
    RECENTS(R.string.act_recents, 0x029F),    // AC Desktop Show All Windows -> Android RECENT_APPS
    PLAY_PAUSE(R.string.act_play_pause, 0x00CD),
    NEXT_TRACK(R.string.act_next, 0x00B5),
    PREV_TRACK(R.string.act_prev, 0x00B6),
    VOLUME_UP(R.string.act_vol_up, 0x00E9),
    VOLUME_DOWN(R.string.act_vol_down, 0x00EA),
    MUTE(R.string.act_mute, 0x00E2);

    final int label;
    private final int androidUsage;

    Action(int label, int androidUsage) {
        this.label = label;
        this.androidUsage = androidUsage;
    }

    void send(Hid hid) {
        if (this != NONE) hid.consumer(androidUsage);
    }

    static Action parse(String name, Action fallback) {
        try {
            return name == null ? fallback : valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
