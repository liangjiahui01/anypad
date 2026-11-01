# Anypad

Turn an Android phone into a Bluetooth trackpad and remote for any screen: a tablet on a stand, a TV box, a computer.
The other device needs nothing installed. It sees an ordinary Bluetooth mouse and keyboard.

Anypad is made for use **without looking at the phone**, for example lying in bed with the tablet above you:

- The whole screen is the trackpad. Gestures differ by finger count, never by where they start.
  - One finger moves the pointer, a tap clicks.
  - Press and hold, then drag, to select or drag.
  - Two fingers anywhere scroll: as a finger swipe (with fling) or as a mouse wheel.
- The volume keys do the common actions, with no button to find: by default
  volume down is **Back**, volume up is **play / pause**, holding volume up goes **Home**.
  Every key's short and long press can be remapped in Settings.
- A vibration confirms each click; the phone's screen stays on, dimmed, while connected.

[中文说明](README.zh-CN.md)

## Requirements

- Phone: Android 9 or later, with Bluetooth HID device support (most phones have it).
- Other side: anything that accepts a Bluetooth mouse. Key mappings currently target Android (Back, Home,
  recent apps, media keys); other systems get their own key tables later.

## Use

1. Open Anypad and allow *Nearby devices*.
2. Tap **Choose a device**:
   - a device already paired with the phone connects directly;
   - otherwise pick **Pair a new device**, then pair with the phone from the tablet's Bluetooth settings.
3. Next time Anypad reconnects to the last device by itself.

Only one app at a time can use the phone as a Bluetooth mouse; close other such apps first.
If the tablet was paired through another mouse app before, forget the pairing on both sides and pair again,
so the tablet learns Anypad's controls.

## Build

```sh
cd android
./gradlew assembleRelease
```

Signed with `android/keystore.properties` when present, else the debug key. Tagged pushes (`v*`) build a signed
APK on GitHub Actions and attach it to the release.

## License

Apache-2.0
