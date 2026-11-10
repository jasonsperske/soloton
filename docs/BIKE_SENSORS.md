# Bike sensors: can a sideloaded app read cadence? (WP6 spike)

**Verdict: a supported route exists, with caveats.** Two Peloton system services on the original Bike tablet hand cadence, power and resistance to any app that binds them. Neither needs root or a signature permission on the firmware that was tested. All the published evidence comes from newer original-Bike firmware (Android 10 and 11). **No source confirms this on Android 7.0**, so which service our tablet has, and whether it is guarded there, is unverified until the adb checks below are run.

## Routes

### A. Affernet `IV1Interface` (callback over Binder). Primary on current original-Bike firmware
- Bind an Intent with action `com.onepeloton.affernetservice.IV1Interface` and package `com.onepeloton.affernetservice` (component `AffernetService`). [Grupetto v1new/Binder.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1new/Binder.kt)
- Use raw `transact` with descriptor `com.onepeloton.affernetservice.IV1Interface`. Code 1 is `registerCallback(IV1Callback, String clientId)` and code 2 is unregister. The service pushes to our Binder with descriptor `...IV1Callback`, code 1 `onSensorDataChange`: `int hasData`, then `BikeData`, whose fields start `long rpm, long power (centiwatts), long, long, int currentResistance (0–100)`. [Grupetto CallbackSensor.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1new/CallbackSensor.kt), [doudar fork V1NewCombinedSensor.kt / BikeCoreData.kt](https://github.com/doudar/grupetto/tree/main/app/src/main/java/com/spop/poverlay/sensor)
- Permissions: OpenRide reports `exported=true` with **no** `android:permission`. It verified this live on PLTN-RB1VQ, Android 11, at about 1 Hz while a rider pedalled (cadence 21–90 rpm). [OpenRide SENSOR_PROTOCOL.md](https://github.com/digitalducktape/OpenRide/blob/main/docs/SENSOR_PROTOCOL.md), [DEVICE.md](https://github.com/digitalducktape/OpenRide/blob/main/docs/DEVICE.md). Grupetto also declares `onepeloton.permission.ACCESS_SENSOR_SERVICE`, which costs nothing to add.
- Bike vs Bike+: Bike+ (PLTN-TTR01) streams over `IBikeInterface` instead. There, `IV1Interface` binds but sends no frames (OpenRide). Tread uses a different interface. This route is for the original Bike only.
- Stock app: not needed. The data comes from a separate system package, and Peloverlay reads it outside a workout. ([darknoon/peloverlay](https://github.com/darknoon/peloverlay))
- History: [Grupetto PR #10](https://github.com/selalipop/grupetto/pull/10) (Sept 2025) added this route because "the old sensor service isn't there anymore on the latest update".

### B. Legacy `com.peloton.service.SensorData` (Messenger). Likely on older firmware such as Android 7 (unverified)
- Bind action `android.intent.action.peloton.SensorData` with category `com.peloton.sensor.category.BIKE`, package `com.peloton.service.SensorData`, component `com.peloton.sensor.SensorService`. The descriptor is `android.os.IMessenger`.
- doudar fork, verified live on 2026-10-18: send message `what=12` with Bundle `command` set to a fixed hex packet and a `requestId`. Cadence is `F5,41,36,F6`, power is `F5,44,39,F6` (tenths of a watt) and resistance is `F5,49,3E,F6`. The reply has `what=14`, with `responseHexString` such as `F1 41 03 <ASCII digits, LSB first> <sum> F6`. [SensorDataProtocol.kt](https://github.com/doudar/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1legacy/SensorDataProtocol.kt), [SensorDataClient.kt](https://github.com/doudar/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1legacy/SensorDataClient.kt), [pedal-calibration.md](https://github.com/doudar/grupetto/blob/main/docs/pedal-calibration.md)
- The original 2022 Grupetto used `what=1/2/3` (repeating rpm, power and resistance) and read the float in `data`. [v1/Sensor.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1/Sensor.kt). Unverified on current firmware.
- Permissions: there is no service permission in SensorData 2.8.3016's manifest (doudar). Android 7 builds are unverified.
- **Caveat:** Affernet and SensorData both open `/dev/ttyO0` with `TIOCEXCL`. While Affernet is running, SensorData fails with "could not open /dev/ttyO0". Use B only when Affernet is absent, and never force-stop Affernet.

### C. Dead ends for an app
- **Direct serial port:** `/dev/ttyO0` at 19200 8N1 is system-owned and not openable without root (Grupetto README; doudar).
- **Hardware taps:** [SmartSpin2k](https://github.com/doudar/SmartSpin2k) (GPL-2.0, ESP32 on a Y-splitter), DFC (closed hardware) and [PeloMon](https://github.com/ihaque/pelomon) sit on the sensor cable and output BLE. They are not tablet routes.
- **[QZ](https://github.com/cagnulein/qdomyos-zwift) (GPL-3.0):** uses SmartSpin2k or BLE. No tablet-service use found (search not exhaustive).

### Licences
- Grupetto and the doudar fork have **no licence** (open issues [#7](https://github.com/selalipop/grupetto/issues/7) and [#13](https://github.com/selalipop/grupetto/issues/13)), so do not copy their code.
- OpenRide is **Apache-2.0**. Its reconstructed AIDL may be copied with its LICENSE and NOTICE kept.
- Peloverlay states public domain in its README.
- OpenPelo is MIT.
- Safest: write our own ~150 lines from the functional facts above (descriptors, transaction codes, parcel order, packet bytes) and credit the sources.

## Recommended `libs/bike` outline

1. `BikePaceSource(context, fallback: PaceSource) : PaceSource`. It reads a `@Volatile` value, so nothing is allocated per frame.
2. Discovery with `PackageManager.queryIntentServices`: Affernet first, SensorData only if Affernet is not installed. If neither is found, return `fallback` immediately.
3. Affernet: bind, then register with client id `"soloton"`. Decode only rpm, power and resistance (read the two unused longs to stay aligned). Unregister and unbind in `onStop`. Never call codes 3 and up (fake data, calibration).
4. SensorData: poll the three fixed read packets one at a time, about 100 ms apart, with a 1.5 s timeout. Validate the frame and checksum. Send no other commands.
5. State: Connected only after the first valid frame. If no frame arrives within 5 s of binding, or on error or disconnect, switch to `fallback` and retry binding every 30 s.
6. Mapping: `mps = clamp(rpm * 0.042, 0, 6.0)`, so 60 rpm ≈ 2.5 m/s (a jog) and 90 rpm ≈ 3.8 m/s. Smooth with an exponential moving average (τ ≈ 1 s). If no frame arrives for 3 s, decay to 0. Keep the factor in settings. Power to speed via PeloMon's curve is an alternative.
7. Manifest: no new permissions needed. Optionally add `onepeloton.permission.ACCESS_SENSOR_SERVICE`. `<queries>` is not needed on API 24.
8. Tests: JVM tests decode synthetic parcels and packets. The bind is checked on the bike.

## Confirm on the real bike (read-only)

```sh
adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell getprop ro.build.display.id; adb shell settings get global peloton_platform
adb shell pm list packages -f | grep -i -E 'peloton|affernet|sensor'
adb shell dumpsys package com.onepeloton.affernetservice   # Service Resolver Table, exported, permission
adb shell dumpsys package com.peloton.service.SensorData
adb shell pm list permissions -f | grep -i -A4 'onepeloton\|peloton'   # protectionLevel
adb shell dumpsys activity services com.onepeloton.affernetservice    # who is bound (stock app?)
adb shell dumpsys activity services com.peloton.service.SensorData
adb shell ps | grep -i -E 'affernet|sensor|peloton'
adb shell ls -l /dev/ttyO* /dev/ttyS*
adb shell pm path com.onepeloton.affernetservice   # then adb pull <path> for offline manifest check
adb logcat -d | grep -i -E 'affernet|SensorData|ttyO0|SerialHandler'
```

Decide: which service exists; whether it is exported with no permission (or a non-signature one); which process owns the serial port; whether transaction codes match (needs the pulled APK or a test build).
