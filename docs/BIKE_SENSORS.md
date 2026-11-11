# Bike sensors: reading cadence, power and resistance from a sideloaded app

**Where this stands.** The bike library (`libs/bike-core` and `libs/bike`, usable outside this repository: see [libs/bike/README.md](../libs/bike/README.md)) implements both routes below. **The owner reported on 2026-11-10 that live sensor data works on their bike.** That report is all that is recorded: which route connected, the sample rate and whether the units match are not written down, because no diagnostics report has been saved, and resistance has not been tested on the bike. The automated tests still run only against stand-in services on an API 24 emulator. Every published observation by others comes from original-Bike tablets on Android 10 or 11.

| Claim | Evidence |
|---|---|
| The serial protocol between tablet and bike | PeloMon, captured on the cable of a real bike (2020) |
| Route A names, codes, field order, units | Grupetto source; OpenRide ran it on a real original Bike, Android 11 |
| Route B polled reads | Grupetto fork ran it once on a real bike (2026-10-18), stationary, after stopping Affernet |
| Route B repeating commands | Grupetto's original 2022 reader; no published capture |
| Our client decodes all of the above correctly | Our JVM tests, plus instrumented tests against our own stand-ins. Not a real bike |
| Any of it on the owner's bike | Owner's report, 2026-11-10: live sensor data works. Route, units and resistance not recorded |

## The protocol underneath: tablet to bike over serial

Documented by the [PeloMon project](https://github.com/ihaque/pelomon) ([write-up](https://ihaque.org/tag/pelomon.html)), which tapped the cable. The tablet is the master; it sends a 4-byte request and the bike's sensor board answers. 19200 baud, 8N1, on the tablet's `/dev/ttyO0`.

```
request    F5 <op> <sum> F6                  read a live value
           F7 <i>  <sum> F6                  read entry i (00 to 1E) of the resistance table
response   F1 <op> <n> <n ASCII digits, least significant first> <sum> F6
```

- `<sum>` is the low byte of the sum of all bytes before it. A response is `n + 5` bytes long.
- The value is decimal, one ASCII digit per byte, **least significant digit first**: `31 33 30` is 031.
- `41` cadence, 3 digits, rpm.
- `44` power, 5 digits, **tenths of a watt**.
- `4A` raw resistance, 4 digits: a sensor count, not a percentage. The tablet converts it with a 31-entry calibration table it reads from the bike at start-up (`F7 00` to `F7 1E`, 4 digits each). Entry i is the count at i x 100/30 percent; a reading is placed by linear interpolation between the two entries around it.
- `49` resistance already normalised to 0 to 100, 3 digits. Not in PeloMon; found by the Grupetto fork ([pedal-calibration.md](https://github.com/doudar/grupetto/blob/main/docs/pedal-calibration.md)), which read `F1 49 03 31 33 30 D1 F6` (31) from a real bike. Whether older sensor boards answer `49` is unknown, so `libs/bike` falls back to `4A` plus the table.
- The tablet polls about every 100 ms. Speed is not sent; the Peloton software computes it from power.

An app cannot use this directly: `/dev/ttyO0` is owned by `system`, mode 0660, SELinux enforcing (Grupetto README; fork notes). The two services below own it on the app's behalf.

## Route A: Affernet `IV1Interface` (callbacks over Binder)

Primary on current original-Bike firmware.

- **Bind:** an Intent with action `com.onepeloton.affernetservice.IV1Interface` and package `com.onepeloton.affernetservice`, flag `BIND_AUTO_CREATE`. The service class is `com.onepeloton.affernetservice.AffernetService` (OpenRide). Grupetto binds by action and package only: the `...AffernetService` string in its [v1new/Binder.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1new/Binder.kt) is passed to the Intent constructor as an action and then replaced, so it is not a component name. *(Correction: an earlier version of this page said Grupetto binds that component.)*
- **Interface descriptor:** `com.onepeloton.affernetservice.IV1Interface`. Raw `transact`, no AIDL published.
  - code 1 `registerCallback(IV1Callback, String clientId)`: interface token, strong binder, string; reply carries the usual exception header.
  - code 2 `unregisterCallback(IV1Callback, String clientId)`: same arguments.
  - code 3 `setFakeDataMode(boolean)`, code 4 `setCallbackReportRate(int)`, codes 5 to 15 calibration and similar (OpenRide). `libs/bike` sends only 1 and 2.
- **Callback descriptor:** `com.onepeloton.affernetservice.IV1Callback`, called **one-way** (OpenRide, from the proxy bytecode).
  - code 1 `onSensorDataChange(BikeData)`: `int hasData`, then if non-zero the `BikeData` parcelable.
  - code 2 `onSensorError(long)`.
  - code 3 `onCalibrationStatus(int, boolean, long)`.
- **`BikeData` starts:** `long rpm`, `long power`, `long stepperMotorPosition`, `long loadCellReading`, `int currentResistance`, `int targetResistance`, then about fifty more fields (strings, a byte array, error codes). Source: [Grupetto BikeData.java](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/BikeData.java), [fork BikeCoreData.kt](https://github.com/doudar/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/BikeCoreData.kt). `libs/bike` reads the first five and stops.
- **Units:** rpm as is; power in **centiwatts** (Grupetto [V1PowerSensor.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1new/V1PowerSensor.kt) divides by 100; OpenRide's live capture agrees); resistance already 0 to 100.
- **Rate:** about 1 Hz on the original Bike, including idle frames with nobody pedalling (OpenRide: 89 frames in 90 s). OpenRide asks for a rate with code 4; we do not.
- **Permissions:** OpenRide reports the service `exported=true` with no `android:permission` on PLTN-RB1VQ, Android 11, build RQ.250113.A ([SENSOR_PROTOCOL.md](https://github.com/digitalducktape/OpenRide/blob/main/docs/SENSOR_PROTOCOL.md)). Grupetto declares `onepeloton.permission.ACCESS_SENSOR_SERVICE`; so do we, at no cost.
- **Quirks:**
  - Resistance "occasionally spikes for a single reading"; both Grupetto versions show the lowest of the last three ([PelotonBikeSensorInterfaceV1New.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/interfaces/PelotonBikeSensorInterfaceV1New.kt)). So do we.
  - "At times the Peloton sensor service stops responding until the Bike is power cycled" (Grupetto [DeadSensorDetector.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/DeadSensorDetector.kt), which warns after 10 s of silence). We report `NoData` after 3 s and rebind after 10 s; a rebind may not cure it.
  - On Affernet 2.4.490, when the last client of a given bind Intent unbinds, the service's `onUnbind` stops its communication with the bike, even if clients bound with a different Intent remain (fork, [SENSOR_CONNECTIONS.md](https://github.com/doudar/grupetto/blob/main/docs/SENSOR_CONNECTIONS.md)). Our `stop()` unbinds. If Peloton's own app is running at the same time and loses its data when ours stops, this is why.
  - Bike+ (PLTN-TTR01) binds `IV1Interface` but never sends a frame; it uses `IBikeInterface` (OpenRide). Tread differs again. This library is for the original Bike.

## Route B: legacy `com.peloton.service.SensorData` (Messenger)

Possibly what Android 7 firmware has. Unverified there.

- **Bind:** action `android.intent.action.peloton.SensorData`, package `com.peloton.service.SensorData`. The fork adds category `com.peloton.sensor.category.BIKE` and found the class `com.peloton.sensor.SensorService` (version 2.8.3016); the service also advertises `FAKE_DATA` and `AURORA` categories, which select other modes and must not be sent. Grupetto's original reader ([v1/Binder.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1/Binder.kt)) sends no category. `libs/bike` tries with the bike category and, if nothing resolves, without.
- **Interface descriptor:** `android.os.IMessenger`.
- **Dialect 1, polled raw reads** (fork, [SensorDataClient.kt](https://github.com/doudar/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1legacy/SensorDataClient.kt)): send `what=12` with a Bundle of `command` (the request packet as comma-separated hex, for example `F5,41,36,F6`) and `requestId`, and a `replyTo`. The answer is `what=14` with the same `requestId` and `responseHexString`: the board's raw response as hex bytes, or the literal `TIME_OUT`. The client validates and decodes the packet itself, as in the serial section above.
- **Dialect 2, repeating commands** (Grupetto's original reader, [v1/Sensor.kt](https://github.com/selalipop/grupetto/blob/main/app/src/main/java/com/spop/poverlay/sensor/v1/Sensor.kt)): send `what=1` (cadence), `2` (power) or `3` (resistance) once with a `requestId` and a `replyTo`; the service keeps sending messages whose Bundle has `data` (float), `time` (epoch long) and `responseHexString`. A reply with negative `what` means the command was refused. No "stop" message is known. *(Addition: the earlier version of this page gave the message numbers but not the reply layout.)*
- **Units, and one open question:** the raw packet's power is tenths of a watt (PeloMon; fork). For the pre-parsed `data` float, Grupetto's original reader divides power by 100, while the fork's notes say `data` "already has this scaling" of one decimal place. These disagree and no capture has been published. `libs/bike` therefore decodes the raw packet whenever it is there, uses `data` (with Grupetto's divide-by-100) only when it is not, and logs the first raw reply of each kind so a real bike can settle it. Pace uses cadence only, so the game does not depend on the answer.
- **Permissions:** the fork found no service permission in SensorData 2.8.3016's manifest. Android 7 builds are unverified.
- **The serial port is exclusive.** Affernet opens `/dev/ttyO0` with `TIOCEXCL`. While Affernet runs, SensorData logs "could not open /dev/ttyO0" and returns nothing; the fork's one successful read needed Affernet stopped first. We never stop Affernet. On a tablet that has both, route B will simply stay silent.

## What `libs/bike` does

See [libs/bike/README.md](../libs/bike/README.md) for the API. In short: try A, then B polled, then B repeating, each for 5 s, skipping any whose package is absent; report `Unavailable` with reasons if none delivers; retry with a growing pause unless nothing is installed at all.

One difference from the Grupetto fork is deliberate. The fork never tries route B when an Affernet service is installed, even a silent one, so as not to compete for the serial port. We do try it after 5 s of silence, as the owner asked. Trying is harmless when Affernet holds the port (B gets nothing). The risk is a tablet where Affernet is installed but not holding the port: SensorData would open it, and Affernet could not until we unbind. If the report from the bike shows both packages, we should revisit this.

## Dead ends for an app

- **The serial port itself:** not openable without root (above).
- **Hardware taps:** [PeloMon](https://github.com/ihaque/pelomon), [SmartSpin2k](https://github.com/doudar/SmartSpin2k) and DFC sit on the sensor cable and rebroadcast over Bluetooth. They are not tablet routes.
- **[QZ](https://github.com/cagnulein/qdomyos-zwift):** uses SmartSpin2k or Bluetooth. No use of the tablet services was found in the earlier survey (not exhaustive, not rechecked).

## Licences

Soloton is GPL-3.0-only.

- **Grupetto** and the **doudar fork**: no licence (open issues [#7](https://github.com/selalipop/grupetto/issues/7) and [#13](https://github.com/selalipop/grupetto/issues/13)). No code, comments or structure were copied; only protocol facts.
- **PeloMon**: the repository's `LICENSE` is GPL-3.0 and the Python scripts in `peloton_decoding/` say GPL v3 in their headers. **But the firmware sources in `pelomon/`, including `peloton.h` (the packet decoder) and `resistance_lut.h` (the table lookup), say "Licensed under the CC-BY-NC 4.0 license" in their own headers.** CC BY-NC is not compatible with the GPL, so nothing was ported from those files. Our decoder and interpolation are written from the documented behaviour.
- **OpenRide**: Apache-2.0, compatible with GPL-3.0. Nothing was copied.

## Verify on the owner's bike

The owner has seen live sensor data on the bike (2026-11-10), but no report from these steps has been saved, so the questions at the end are still open, resistance included. Steps 1 to 3 need an app that includes `libs/bike` installed on the bike (for Roadwork, application id `dev.soloton.roadwork`).

1. Open the diagnostics screen:
   ```sh
   adb shell am start -n dev.soloton.roadwork/dev.soloton.bike.BikeDiagnosticsActivity
   ```
2. Wait 20 seconds, then: pedal slowly and quickly (cadence should follow); turn the knob end to end (resistance should run from near 0 to near 100); pedal hard (power should rise); stop (cadence and power 0, state still Connected).
3. Tap **Copy report** and pull the file:
   ```sh
   adb pull /sdcard/Android/data/dev.soloton.roadwork/files/bike-diagnostics.txt
   ```
4. Run these read-only checks and save the output with the report:
   ```sh
   adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
   adb shell getprop ro.build.display.id; adb shell settings get global peloton_platform
   adb shell pm list packages -f | grep -i -E 'peloton|affernet|sensor'
   adb shell dumpsys package com.onepeloton.affernetservice   # service table, exported, permission, version
   adb shell dumpsys package com.peloton.service.SensorData
   adb shell pm list permissions -f | grep -i -A4 'onepeloton\|peloton'   # protectionLevel
   adb shell dumpsys activity services com.onepeloton.affernetservice    # who else is bound
   adb shell dumpsys activity services com.peloton.service.SensorData
   adb shell ps | grep -i -E 'affernet|sensor|peloton'
   adb shell ls -l /dev/ttyO* /dev/ttyS*
   adb logcat -d | grep -i -E 'affernet|SensorData|ttyO0|SerialHandler'
   ```

What the report settles:

- Which packages exist on Android 7.0, their versions, and whether the services are exported and unguarded.
- Which route connects, and how many samples a second it gives.
- Whether cadence, power and resistance are in the units assumed (the event log holds the first raw reply of each kind on route B).
- On route B: whether the polled dialect is understood, whether the board answers `49`, and what the `data` float's power scale is.
- If nothing connects: the reason each route gave, which says what to change.
