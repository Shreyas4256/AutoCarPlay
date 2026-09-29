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
AutoCarPlay has two ways onto the car screen. Both show the same content:

```
CarContent (normal Android views)
   ├─ Media3 ExoPlayer PlayerView  (phone videos, links, HLS/DASH)
   ├─ WebView                     (YouTube / websites)
   └─ TextureView ◄── MediaProjection (phone screen mirror)
```

1. **Car activity (for sideloaded installs).** `CarScreenActivity` is declared with the
   `CAR_LAUNCHER` category, so Android Auto opens it full screen on the car display while the car
   is parked. Android Auto only allows these "parked apps" in the **games** category, so the app is
   marked `android:appCategory="game"`. Google's testing guide says Android Auto's *Unknown sources*
   setting covers parked apps, so this works without the Play Store. It needs an **Android 15+
   phone** and a recent Android Auto. Touches arrive as normal touch events.
2. **Car App Library surface (for Play Store installs).** `VideoCarAppService` is a
   `androidx.car.app` app in the **navigation** category, which gets a raw drawing `Surface` (meant
   for maps). The content is drawn on it through a `VirtualDisplay` + `Presentation`, and the taps
   and drags Android Auto reports are replayed as touch events. Android Auto **does not list Car
   App Library apps that were sideloaded**, even with *Unknown sources* on. They must come from a
   trusted source such as Google Play (a Play Console internal test works without review).

When mirroring, the optional accessibility service replays car-screen taps on the phone screen.

Main code:

* `car/CarContent.kt`: the views on the car screen, lists, and touch handling.
* `car/CarController.kt`: player, web page, mirroring and modes; shared by both routes.
* `car/CarScreenActivity.kt`: route 1, the parked car activity.
* `car/CarSurfaceHost.kt`, `CarPresentation.kt`: route 2, drawing onto the Car App Library surface.
* `car/MainScreen.kt`, `MenuScreen.kt`, `LibraryScreens.kt`: route 2's Android Auto templates (buttons, lists, keyboard).
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

Requirements for a sideloaded install: an **Android 15 or newer** phone and an up-to-date
Android Auto (games support reached general availability in September 2026).

1. Update **Android Auto** from the Play Store.
2. Open Android Auto settings: phone **Settings → Connected devices → Connection preferences →
   Android Auto** (or the button in the AutoCarPlay app).
3. Scroll down and tap **Version** 10 times, then allow developer settings.
4. Tap **⋮ → Developer settings** and turn on **Unknown sources**.
5. Go back, tap **Customize launcher**, and make sure **AutoCarPlay** is ticked.
6. Reconnect the phone to the Nexon (unplug and plug in again). While **parked**, open
   **AutoCarPlay** from the Android Auto app launcher.

On Android 14 or older, Android Auto won't list a sideloaded AutoCarPlay. The option there is to
install it through Google Play, for example as a Play Console internal test ($25 one-time developer
account; internal tests skip review).

In the AutoCarPlay phone app, also allow **video access** and **notifications**.

### Optional: touch control while mirroring

Phone **Settings → Accessibility → Installed apps → AutoCarPlay touch control → On**.
On Android 13+, if the switch is greyed out ("restricted setting"): **Settings → Apps →
AutoCarPlay → ⋮ → Allow restricted settings**, then try again.

## Using it

* Open **AutoCarPlay** on the car screen. Tap a tile (Phone videos, YouTube, Saved links,
  Mirror phone). The Play Store version also has a **Menu** button.
* Tap the picture to use it like a touchscreen. For example, tap the video to show the player
  controls, or tap YouTube's full-screen button.
* In the car activity, the back and close buttons (top right) leave web pages and mirroring. In the
  Play Store version, the buttons at the edge change with what's playing: play/pause, stop, ±10 s,
  fit/fill (videos); back, keyboard, scroll (web); back, home, stop (mirroring).
* From the phone app you can paste a link and tap **Play on car**, pick a phone video, or open
  YouTube. Requests made before the car screen is open start as soon as you open AutoCarPlay in the car.

## Known limitations

* **Netflix, Prime Video, Hotstar and similar DRM apps** show a black picture when mirrored.
  Android blocks capturing protected video. YouTube works in the built-in browser and when mirrored.
* The car keyboard only works while parked (an Android Auto rule). You can type links on the phone instead.
* Mirroring needs the phone screen on and unlocked. Turn the phone sideways for a full-width picture.
  Android asks for permission ("Start now") each time mirroring starts.
* The car activity only runs while parked, and the phone lists AutoCarPlay as a game.
* Google may change Android Auto in ways that affect sideloaded apps. If AutoCarPlay disappears
  from the car launcher, re-check **Unknown sources** and **Customize launcher**.
