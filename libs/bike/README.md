# libs/bike: read the bike's sensors

Reads live cadence (rpm), power (watts) and resistance (0 to 100) from the sensor service that Peloton's own software runs on the original Bike's tablet, and turns cadence into a `PaceSource` for games.

**Status: operational on the owner's bike.** The owner reported on 2026-11-10 that live sensor data works on the real bike. Resistance has not been tested there, and which route connected and whether the units match are not yet recorded. The automated tests run against stand-in services written from public descriptions of the protocol. See "Check it on a real bike" below, and [docs/BIKE_SENSORS.md](../../docs/BIKE_SENSORS.md) for the evidence.

## What it does

An app cannot open the tablet's serial port to the bike. Two Peloton system services can, and both hand the values to any app that binds them. `BikeSensors` tries them in order and gives each 5 seconds to produce a valid sample:

| | Route | How it works |
|---|---|---|
| A | `com.onepeloton.affernetservice`, interface `IV1Interface` | Register a callback Binder; the service pushes a `BikeData` frame about once a second. Cadence in rpm, power in centiwatts, resistance 0 to 100. |
| B | `com.peloton.service.SensorData`, a Messenger | Polled: message 12 carries a raw read packet for the bike's sensor board, message 14 brings the board's raw answer, which this library checks and decodes (power in tenths of a watt). |
| B, older dialect | same service | Messages 1, 2 and 3 ask the service to keep sending cadence, power and resistance. Tried last. Can be switched off with `BikeEndpoints(allowRepeatingCommands = false)`. |

A route whose package is not installed is skipped. What happens when nothing works:

- **Neither package installed** (an emulator, a phone, another tablet): the state is `Unavailable` with the reason, and nothing is retried.
- **Installed but silent or refusing**: `Unavailable` with what each route did, and the search repeats after 1 s, 2 s, 4 s and so on up to 30 s.
- **A working connection goes quiet**: `NoData` after 3 s; after 10 s the binding is dropped and the search starts again.
- **The service's process dies**: `Error`, then the search starts again after the pause.

The library only binds and reads. It never stops, restarts or reconfigures a Peloton service, and never sends the calibration or fake-data calls those services also offer. Messages that are malformed or carry impossible values (negative, over 250 rpm, over 3000 W, resistance over 105) are dropped whole.

Two details worth knowing:

- Resistance is reported as the lowest of the last three samples, because the sensor is known to spike for a single sample. A real increase therefore shows two samples late. `BikeSensors(context, filterResistance = false)` turns this off.
- On route B, if the board never answers the "resistance, already normalised" read, the library reads the raw resistance count instead and converts it with the 31-entry calibration table stored on the bike, as the tablet's own software does. Until that table is loaded, resistance reads 0.

## Use it in an app

Add `implementation(project(":libs:bike"))` to the app's Android module, then:

```kotlin
val bike = BikePaceSource(applicationContext)        // in onCreate
val pace: PaceSource = bike.orElse(buttonsPace)      // give this to the game instead of buttonsPace
// onResume: bike.start()        onPause: bike.stop()
```

`pace.metersPerSecond` follows the bike while `bike.isLive`, and `buttonsPace` (any other `PaceSource`, such as the on-screen slower/faster buttons) the rest of the time. Show `bike.isLive` in the HUD so the rider can tell which one is driving. Both are cheap, lock-light reads meant for the render thread.

The same object carries the resistance knob as a 0 to 1 `ResistanceSource`: `bike.resistanceOrElse(fallback)` follows the bike while it is live and `fallback` otherwise, and reads 0 if the bike goes quiet for 3 s. Roadwork passes it to its environments, where Skylift Mesa uses it for height and difficulty ([docs/ENVIRONMENTS.md](../../docs/ENVIRONMENTS.md#resistance)). Resistance has not yet been checked on a real bike.

The pace mapping is `metres per second = clamp(rpm x 0.042, 0, 6.0)`, so 60 rpm is about 2.5 m/s and 90 rpm about 3.8 m/s. It is smoothed with a 1 s time constant and eases to a stop if no sample arrives for 3 s. Change it with `BikePaceSource(BikeSensors(context), CadencePace(metersPerSecondPerRpm = ..., maxMetersPerSecond = ...))`.

For the raw values, use `BikeSensors` directly: `start()`, `stop()`, `state`, `latest` (a `BikeReading`), `addListener(...)` and `diagnostics()`.

The library's manifest adds the `onepeloton.permission.ACCESS_SENSOR_SERVICE` permission request, `<queries>` entries for the two packages (only needed on Android 11 and later) and the diagnostics screen.

## Check it on a real bike

Any app that includes this library also contains a diagnostics screen. With the app installed (for Roadwork the application id is `dev.soloton.roadwork`):

```sh
adb shell am start -n <application id>/dev.soloton.bike.BikeDiagnosticsActivity
```

1. Wait about 20 seconds without touching the bike. Note the state line and the route.
2. Pedal slowly, then quickly. Cadence should follow your legs.
3. Turn the resistance knob from one end to the other. Resistance should go from near 0 to near 100.
4. Pedal hard for a few seconds. Compare the power shown with what you would expect.
5. Stop pedalling. Cadence and power should fall to 0 and the state should stay Connected.
6. Tap **Copy report**, then fetch the file it names:

```sh
adb pull /sdcard/Android/data/<application id>/files/bike-diagnostics.txt
```

Send back that file, and say whether the numbers in steps 2 to 5 looked right. If the state never reaches Connected, the file still says which packages exist and what each route did, which is what is needed to fix it. The read-only `adb` checks in [docs/BIKE_SENSORS.md](../../docs/BIKE_SENSORS.md) add detail the app cannot see.

## Layout

- `BikeReading.kt`, `AffernetWire.kt`, `SensorDataWire.kt`, `CadencePace.kt`, `BikeLink.kt`, `BikeDiagnostics.kt`: pure Kotlin, no Android imports. Wire layouts and unit conversion, validation, the resistance table, the pace mapping, the connection state machine and the report text. Unit tested on the JVM (`src/test`).
- `BikeSensors.kt`, `Transports.kt`, `BikePaceSource.kt`, `BikeDiagnosticsActivity.kt`: the Android layer.
- `src/androidTest`: stand-ins for both Peloton services, in a separate process and speaking the same Binder and Messenger protocols, and the tests that run the client against them. They are in the test APK only.

```sh
./gradlew :libs:bike:check                       # JVM tests and lint
./gradlew :libs:bike:connectedDebugAndroidTest   # needs an emulator or device
```

The instrumented tests prove the client and the stand-ins agree, and that the client copes when a service is missing, silent, wrong or killed. Both sides were written from the same public descriptions, so the tests cannot show that those descriptions match a real bike.

## Credits and licence

This library is GPL-3.0-only, like the rest of the repository. It contains no code from Peloton and none copied from the projects below; it was written from the protocol facts they document.

- **PeloMon** by Imran S. Haque ([repository](https://github.com/ihaque/pelomon), [write-up](https://ihaque.org/tag/pelomon.html)): the serial protocol between tablet and bike, on which all of this rests. Packet framing, the additive checksum, digits sent least significant first, the cadence, power and raw-resistance commands, power in tenths of a watt, and the 31-entry resistance table with linear interpolation. The repository's licence file is GPL-3.0, but the firmware sources that hold the decoder and the table code say CC BY-NC 4.0 in their headers, so nothing was ported from them: the interpolation here is written from the description.
- **Grupetto** ([selalipop/grupetto](https://github.com/selalipop/grupetto)) and its maintained fork ([doudar/grupetto](https://github.com/doudar/grupetto)): the protocol reference for both tablet services. Service and interface names, transaction codes, the order of fields in `BikeData`, the Messenger message numbers and Bundle keys, and the resistance spike. Neither repository has a licence, so only facts were taken.
- **OpenRide** ([digitalducktape/OpenRide](https://github.com/digitalducktape/OpenRide), Apache-2.0): confirmation of route A on a real original Bike (Android 11), including that the callback is one-way and that power is in centiwatts. Nothing was copied.

Not affiliated with or endorsed by Peloton Interactive, Inc. "Peloton" is used only to name the hardware this works with.
