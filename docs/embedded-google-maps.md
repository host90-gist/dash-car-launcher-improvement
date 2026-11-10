# Embedded Google Maps in QCarDash

## What changed

The original QCarLauncher APK embeds `com.google.android.gms.maps.SupportMapFragment` in its navigation tile. This fork now uses the equivalent `com.google.android.gms.maps.MapView` in `QCarHomeActivity`, because the lightweight launcher is a framework `Activity` rather than an AndroidX fragment host.

The view has a QCar-style dark map theme, compass/reset, recenter, zoom +/- and a GPS-speed bubble. The surrounding Poweramp, AdBlue and vehicle panels remain native Android views with no transition animations.

The sidebar now contains only **Home, Apps, Maps (recenter), Settings**. Maps is a real embedded display: the sidebar Maps control does *not* launch an external application.

## Google Maps authentication is required

1. In [Google Cloud Console](https://console.cloud.google.com/), create or choose a project and enable **Maps SDK for Android**. Set up the billing account required by Google Maps Platform.
2. Create a Maps API key. Restrict it to **Maps SDK for Android** and to the app's Android package **`org.qcardash.launcher`** and the **SHA-1 fingerprint of the certificate that signs the APK**.
3. For a local build, add this to your root `local.properties` (gitignored):

   ```properties
   MAPS_API_KEY=YOUR_MAPS_SDK_FOR_ANDROID_KEY
   ```

   The key can alternatively be supplied in the `MAPS_API_KEY` environment variable, suitable for GitHub Actions secrets. Do not commit any real key.
4. Check that your Android device has working Google Play services and Internet connectivity.

**Important:** GitHub-hosted runners' automatically generated *debug* signing keys can vary across builds. For a restricted Google Maps API key to work consistently, configure a **stable signing certificate** and use its actual SHA-1 fingerprint. The source-code changes here do not configure that keystore. An APK can build successfully but still show a blank map if the key/certificate restrictions do not match.

With no API key, the launcher deliberately shows a configuration explanation in the map tile instead of drawing a fake grid or silently pretending that the map is functional.

The Maps SDK adds a Google Play services library to the formerly dependency-free Dash project. Other QCarDash UI elements remain native views; actual memory overhead must be measured on the device.

## Android 10 and permission notes

The maps panel uses Android 10-compatible APIs. Precise location permission is required for GPS speed/recentering. Maps rendering itself requires a valid Maps SDK key; displaying a map is not the same as turn-by-turn navigation. Permission/setup controls remain in Settings.

## Build policy

On branch `qcar-android10-poweramp-gps`, the APK workflow is **manual only**. No build was requested or triggered with these edits. Once the key, stable signing identity and UI are ready, trigger the `Android 10 APK build` workflow explicitly.
