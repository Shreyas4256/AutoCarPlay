# AutoCarPlay

Watch videos on your car's **Android Auto** screen (built for the Tata Nexon's Harman
touchscreen, but it works on any Android Auto head unit).

> **Safety:** only watch while the car is parked. Using video while driving is dangerous and
> illegal in India and most other countries.

## What it can do

| On the car screen | How |
|---|---|
| **Phone videos** | Browse the videos stored on your phone and play them (mp4, mkv, webm, …). |
| **YouTube** | Opens YouTube's mobile site right on the car screen; tap to use it like a touchscreen. |
| **Links** | Play any `.mp4` / `.m3u8` (HLS) / `.mpd` (DASH) link, or open any website. |
| **Share to car** | In YouTube/Chrome tap **Share → AutoCarPlay**, and it plays on the car. |
| **Mirror phone screen** | Shows your whole phone screen in the car, so any app works. With *touch control* on, taps on the car screen control the phone. |

Audio plays through the car speakers like any Android Auto audio.

## How it works (the technology)

Android Auto doesn't let normal apps show video, and its official video support only covers
some Android Automotive cars. A Nexon uses phone projection, so that support doesn't apply.
The one opening Android Auto gives third-party apps is the **Android for Cars App Library**
(`androidx.car.app`): apps in the **navigation** category get a raw drawing `Surface` on the car
display (normally used for maps). AutoCarPlay uses that surface:

```
Android Auto (car screen)
   └─ NavigationTemplate  ── the car's buttons (Menu, play/pause, …)
   └─ Surface ◄── VirtualDisplay ◄── Presentation (normal Android views)
                                        ├─ Media3 ExoPlayer PlayerView  (phone videos, links, HLS/DASH)
                                        ├─ WebView                     (YouTube / websites)
                                        └─ TextureView ◄── MediaProjection (phone screen mirror)
```

* **Touch:** Android Auto reports taps and drags on the surface (`SurfaceCallback.onClick/onScroll`).
  They're replayed as touch events on the views above. When mirroring, the optional
  accessibility service replays them on the phone screen.
* **Sideloading:** Google Play doesn't accept this kind of app, so it's installed as an APK, and
  Android Auto needs *Unknown sources* turned on (steps below).

Main code:

* `car/CarDisplayController.kt`: receives the car surface and manages the virtual display, the player and the modes.
* `car/CarPresentation.kt`: what's drawn on the car screen, and the touch replay.
* `car/MainScreen.kt`, `MenuScreen.kt`, `LibraryScreens.kt`: the Android Auto templates (buttons, lists, keyboard).
* `mirror/`: screen capture (`MediaProjection`), the foreground service, and touch control (`AccessibilityService`).
* `phone/`: the phone app (remote control, setup guide, share target).

## Install

1. On your phone, download **AutoCarPlay.apk** from this repository's
   [Releases](../../releases) page (every push builds a new APK with GitHub Actions; it's also
   attached to each workflow run as an artifact).
2. Open it and allow installing from your browser/file manager.

To build it yourself: `./gradlew assembleRelease` (needs JDK 17 and the Android SDK). The APK is in
`app/build/outputs/apk/release/`.

## One-time Android Auto setup

1. Open Android Auto settings: phone **Settings → Connected devices → Connection preferences →
   Android Auto** (or the button in the AutoCarPlay app).
2. Scroll down and tap **Version** 10 times, then allow developer settings.
3. Tap **⋮ → Developer settings** and turn on **Unknown sources**.
4. Go back, tap **Customize launcher**, and make sure **AutoCarPlay** is ticked.
5. Connect the phone to the Nexon (USB, or wireless on newer models). **AutoCarPlay** now appears in
   the Android Auto app launcher on the car screen.

In the AutoCarPlay phone app, also allow **video access** and **notifications**.

### Optional: touch control while mirroring

Phone **Settings → Accessibility → Installed apps → AutoCarPlay touch control → On**.
On Android 13+, if the switch is greyed out ("restricted setting"): **Settings → Apps →
AutoCarPlay → ⋮ → Allow restricted settings**, then try again.

## Using it

* Open **AutoCarPlay** on the car screen. Tap a tile (Phone videos, YouTube, Saved links,
  Mirror phone) or use **Menu**.
* Tap the picture to use it like a touchscreen. For example, tap the video to show the player
  controls, or tap YouTube's full-screen button.
* The buttons at the edge change with what's playing: play/pause, stop, ±10 s, fit/fill (videos);
  back, keyboard, scroll (web); back, home, stop (mirroring).
* From the phone app you can paste a link and tap **Play on car**, pick a phone video, or open
  YouTube. Requests made before the car screen is open start as soon as you open AutoCarPlay in the car.

## Known limitations

* **Netflix, Prime Video, Hotstar and similar DRM apps** show a black picture when mirrored.
  Android blocks capturing protected video. YouTube works in the built-in browser and when mirrored.
* The car keyboard only works while parked (an Android Auto rule). You can type links on the phone instead.
* Mirroring needs the phone screen on and unlocked. Turn the phone sideways for a full-width picture.
  Android asks for permission ("Start now") each time mirroring starts.
* Google may change Android Auto in ways that affect sideloaded apps. If AutoCarPlay disappears
  from the car launcher, re-check **Unknown sources** and **Customize launcher**.
