# OEM screen and no-Google-dependencies navigation

The QCar-style dashboard preserves the light native Android interface.

## OEM screen control

The left navigation dock now contains Home, Apps, **OEM**, and Settings.

The OEM button is based on the **actual original QCarLauncherPreview** APK's `qacc:car_info` implementation:

```java
McuServiceManager manager = McuServiceManager.getInstance();
manager.initialize(context, null);
manager.RPCGeneralRpcCall(152, new byte[] {-94, 1, 0, 0});
```

The fork calls this command through optional reflection (`OemScreenBridge.kt`), rather than bundling the proprietary `navi.McuServiceManager` SDK. On head units where that SDK is absent or the command fails, a message is displayed instead of crashing. This command is **firmware-specific**, and is **not guaranteed** to work on every Mercedes or Audi Android head unit. It has not yet been tested on the user's car hardware.

## Google Maps

The large navigation panel is a lightweight, static display with GPS speed. Tapping it opens the **installed Google Maps app** (`com.google.android.apps.maps`). There is no Maps SDK, no embedded map, no app-owned Maps API key, and no Google Play services dependency in this launcher.

The **Google Maps app itself** may depend on Google Play services on the device; that is separate from this launcher's dependencies. If Google Maps is absent, the launcher shows a message.

The app's own speed display is derived from Android's GPS `LocationManager` and therefore does not require Google Maps.

## Builds

The development branch's Android 10 workflow remains **manual-only**. These changes were source-only and have not been compiled or tested on the head unit.
