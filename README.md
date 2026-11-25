# RemotePhone

No-root phone-to-phone remote control for Android. Install the same app on
two phones on the same Wi-Fi network: one hosts (its screen is shared and
controlled), the other controls it.

## How it works

- **Host (controlled phone):** captures its screen with MediaProjection,
  encodes H.264 with MediaCodec and streams it over local Wi-Fi (TCP 18080).
  Touch/key input arriving on TCP 18081 is injected through an
  AccessibilityService (`dispatchGesture` / global actions / set-text).
- **Controller:** decodes the H.264 stream with MediaCodec, shows it live,
  and sends taps, swipes, back/home/recents and typed text as normalized
  0..1 coordinates.

No root, no ADB, no account, no cloud. Everything stays on the local
network in v1.

## Setup

1. Install the APK on **both** phones.
2. On the phone to be controlled, open RemotePhone → **"This phone is controlled"**,
   then turn on the RemotePhone accessibility service when prompted
   (Settings → Accessibility → RemotePhone). Without it, taps do nothing.
3. On the same screen tap **Start hosting**. It shows this phone's Wi-Fi IP
   and a random 6-digit code.
4. On the controlling phone, open RemotePhone → **"Control another phone"**,
   enter the IP and the code, and tap **Connect**.
5. The controlled phone shows an accept dialog naming the connecting IP.
   Accept, then approve the system screen-capture prompt. The live screen
   appears on the controller.

While a session is live, the controlled phone shows a persistent
"Sharing your screen — tap to stop" notification. Stop any time from the
notification or from the app.

## Honest limits (v1)

- It cannot unlock the lock screen.
- It cannot touch some secure system dialogs (for example permission
  prompts) — those need a finger on the controlled phone.
- Local Wi-Fi only. Both phones must be on the same network.
- Screen capture permission is requested again each session (Android
  requirement).

## Build

Pushes to `main` build a debug APK with GitHub Actions
(`./gradlew assembleDebug`). Requires JDK 17 and Android SDK 34.

## License

MIT.
