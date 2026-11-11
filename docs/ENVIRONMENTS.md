# Environments

An **environment** in Roadwork is a 3D scene paired with the rider's choice of music for it. Each environment starts with one game's folder of music; in the app's music settings the rider can add other folders and tick or untick individual songs ([INSTALL.md](INSTALL.md#choosing-folders-and-songs-on-the-tablet)). Riding in an environment plays only its ticked songs, shuffled without repeats. The rider, the ride and the controls are the same everywhere: elapsed time, distance, pace, the handlebars at the bottom of the screen, the HUD and the music buttons belong to the app, and the world around them belongs to the environment.

## The registry

Environments are listed in `Environments.all` (`apps/roadwork/core/src/main/kotlin/dev/soloton/roadwork/Environments.kt`). The first entry is the default.

| id | Title | Default music folder (under the music root) | Package |
|---|---|---|---|
| `riverfront` | Riverfront Roadwork | `Mike Tyson's Punch-Out!!` | `dev.soloton.roadwork.env.riverfront` |
| `skylift-mesa` | Skylift Mesa | `Space Harrier II` | `dev.soloton.roadwork.env.skylift` |

- **id**: stable and kebab-case. The tablet saves the last environment ridden and each environment's music selection under it, and it is typed on the command line, so never rename one.
- **Title**: shown to the rider (on the picker, under the PAUSED banner and in music settings).
- **Default music folder**: the name of one sub-folder of the music root (`/sdcard/Music/soloton` by default; see [INSTALL.md](INSTALL.md#copying-music-to-the-tablet)). It is what plays until the rider chooses folders and songs for the environment. It is used as a literal folder name, so apostrophes, `!!` and spaces are fine. `null` starts with everything under the root.

## Resistance

The game passes the bike's resistance to every scene as `RideState.resistance`, a fraction from 0 to 1 eased over about half a second. A scene may use it as a difficulty or intensity setting, or ignore it (the riverfront does). It comes from the bike's resistance knob when the bike's sensors are live ([libs/bike](../libs/bike/README.md)). Without a bike it is set by hand: **R** raises it and **F** lowers it by 5% during a ride (Page Up and Page Down do the same), and the desktop launcher takes `--resistance <0-100>`. There is no on-screen control for it.

### Skylift Mesa

A desert trail under a teal sky ([on the ground](../apps/roadwork/docs/skylift-mesa-ground.png), [climbing](../apps/roadwork/docs/skylift-mesa-climbing.png), [high up](../apps/roadwork/docs/skylift-mesa-high.png)). Resistance decides how the ride goes:

- **Up to 15%:** the bike stays on the trail. Dark boulders bounce along the ground toward it and pale crystal "drifters" pass overhead and alongside.
- **Above 15%:** the bike lifts off and climbs, up to 40 m with the knob at its end, among rocks that hang in the air. Turning the resistance down brings it back to the trail.
- **Traffic:** things come the other way about once every eight seconds with no resistance and more than once a second at full resistance, and faster too. None come while the rider is stopped.
- **Dodging:** the rider cannot steer, so the bike swerves by itself around anything on a collision course and returns to the middle of the trail. Nothing can be hit and there is no score.

The rules are in `SkyliftMotion` (pure maths, unit tested); the geometry is in `MesaScenery`.

## The picker

The app opens on a picker ([screenshot](../apps/roadwork/docs/picker.png)): one card per environment, in registry order, with a preview of its scene, its title, a line about its music ("14 of 20 songs from 1 folder", or an amber line when nothing would play), a **Ride** button and a **Music** button that opens that environment's music settings. The environment ridden last is outlined. An **Exit** button at the top right closes the app. No music plays on the picker.

- **Ride** starts a ride from zero in that environment: its playlist is selected, its scene built, music started.
- During a ride, pause and tap **Environments**, or press back (Escape on desktop), to end the ride and return to the picker: music stops and the scene is disposed. On the picker, back or the **Exit** button exits the app.
- The preview is one frame of the scene, 30 simulated seconds into a ride at the default pace, drawn into a framebuffer when the picker opens (no HUD, no handlebars) and freed when a ride starts. It comes from the same `EnvironmentScene` as the ride, so a new environment gets its preview for free.
- The layout (`PickerLayout`) holds one to eight cards: up to four in one row, five to eight in two rows. `PickerLayoutTest` checks every count for overlaps and minimum sizes.

To skip the picker and ride an environment at once:

- Desktop: `roadwork-desktop --env <id>` (`--list-envs` prints the table). `--screenshot` rides the default environment or `--env`; add `--picker` to screenshot the picker instead.
- Android:

  ```bash
  adb shell am start -n dev.soloton.roadwork/.android.RoadworkActivity --es environment riverfront
  ```

- In code: `RoadworkGame(environment = ...)` rides at once; `RoadworkGame(environment = lastRidden, startOnPicker = true)` opens the picker. On the render thread, `ride(environment)` and `showPicker()` move between the two, and `switchEnvironment(id)` changes the scenery of a ride in progress, keeping the ride (elapsed time, distance, pace and pause).

## Music for an environment

Each environment has a playlist, keyed by its id: an ordered list of folders and, for each, the songs that are unticked. Until the rider changes it, the playlist is the environment's default folder with every song ticked. The selection is edited in music settings and saved on the tablet by `libs/music` (`SelectionStore`); `core` only names the playlist (`Environment.playlist`, a `PlaylistRef` of id, title and default folder) and asks the `MusicController` to play it or to summarise it for the picker.

- Folders inside the music root are saved relative to it, so moving the root keeps the selection; folders elsewhere are saved as absolute paths.
- Unticked songs are saved by file name per folder. Songs copied into a folder later are ticked, and the same file name in two folders is two songs.
- A saved folder that is missing is shown greyed as "missing" in settings and skipped when playing; it is not removed, and plays again when it returns.
- When nothing would play (no folders, all missing, no songs, or none ticked) the HUD and the picker say why, for example "No songs selected for Riverfront Roadwork".
- A change made in settings during a ride takes effect on returning to it, without restarting a song that is still ticked.

## How it fits together

| Piece | Where | Role |
|---|---|---|
| `Environment` | `core/.../Environment.kt` | `id`, `title`, `musicFolder`, and `createScene(seed)` |
| `EnvironmentScene` | same file | What the game needs from a scene: `create(ride)`, `resize`, `step(dt, ride)`, `draw(ride)`, `light`, optional development cameras, `dispose` |
| `Environments` | `core/.../Environments.kt` | `all`, `default`, `find(id)` (null if unknown), `byId(id)` (default if unknown) |
| `EnvironmentHost` | `core/.../EnvironmentHost.kt` | Holds the live scene and swaps it; calls `MusicController.usePlaylist(environment.playlist)` |
| `AppFlow`, `RideSession` | `core/.../AppFlow.kt` | Picker or ride, and what happens between them: scene built and disposed, music started and stopped. Unit tested without OpenGL |
| `Picker`, `PickerLayout` | `core/.../Picker.kt`, `PickerLayout.kt` | The picker screen: previews, cards, buttons |
| `RoadworkGame` | `core/.../RoadworkGame.kt` | The libGDX app: picker and rides, rider's bike, HUD; a ride draws scene, then bike, then HUD |
| `PlaylistRef`, `PlaylistSummary` | `libs/api` | A playlist's id, title and default folder; its counts or problem for the picker |
| `MusicController.usePlaylist(playlist)`, `playlistSummary(playlist)`, `stop()` | `libs/api`, implemented in `libs/music` | Play the songs selected for a playlist; describe it; stop on the way back to the picker |
| `PlatformActions.openMusicSettings(playlist)` | `libs/api`, implemented by the launcher | Opens the settings screen for that playlist |
| `MusicSelection`, `SelectionStore`, `SelectionResolver` | `libs/music/.../MusicSelection.kt` | The saved folders and unticked songs, and what they come to on disk |

Shared building blocks a scene can use, all in `dev.soloton.roadwork`: `MeshWriter` and `SceneMeshes` (flat-shaded vertex-colour meshes), `Shaders` (the scene shader with sun, hemisphere ambient and fog), `Rng` (deterministic random numbers), `ChunkTrack` (recycling stretches of an endless path), `RiderCamera` (the view from the saddle), `RideState` (time, distance, speed, resistance, wheel and crank angles).

Rules for a scene:

- **Time moves only in `step`.** The game calls it in fixed 1/60 s steps after advancing the ride. `draw` must depend only on the ride and on what `step` accumulated, so `--screenshot --at <seconds>` gives the same image every time.
- **No OpenGL before `create`.** Constructing a scene must be cheap and GL-free: launchers and tests create scenes just to ask for their camera names.
- **Start from the ride you are given.** After a switch `create(ride)` is called part way through a workout, not at zero, and the picker's preview creates a scene of its own, steps it for 30 seconds, draws it once and disposes it.
- **Light the rider's bike.** `light` supplies the sun and ambient colours the handlebars are drawn with.
- **Stay in budget** ([DEVICE.md](DEVICE.md)): OpenGL ES 2.0, under about 150 draw calls and 60k triangles a frame, no per-frame allocation in `step` or `draw`.
- **Be original.** Scenes are original work; do not reproduce a game's characters, art or music. The music folder is the rider's own files and is never part of this repository.

## Adding an environment

The example adds `night-circuit`, whose default music folder is `Night Circuit Soundtrack`.

1. **Package.** Create `apps/roadwork/core/src/main/kotlin/dev/soloton/roadwork/env/nightcircuit/`. Everything specific to the environment (geometry builders, sky, characters, tuning constants) goes here. Do not edit another environment's package.

2. **Scene.** Write `NightCircuitScene(seed: Long) : EnvironmentScene`. `env/riverfront/RiverfrontScene.kt` is the worked example: it compiles the shared scene shader, builds static meshes once in `create`, recycles path chunks with `ChunkTrack`, places its camera with `RiderCamera.place(camera, ride)` and exposes its sky as `light`.

3. **Definition.** In the same package:

   ```kotlin
   object NightCircuit {
       val environment = Environment(
           id = "night-circuit",
           title = "Night Circuit",
           musicFolder = "Night Circuit Soundtrack",
       ) { seed -> NightCircuitScene(seed) }
   }
   ```

4. **Registry entry.** Add one line to `Environments.all`:

   ```kotlin
   val all: List<Environment> = listOf(
       Riverfront.environment,
       NightCircuit.environment,
   )
   ```

   Nothing else needs wiring: the picker, the desktop launcher, the Android launcher, music and the settings screen all read the registry. The picker holds up to eight environments.

5. **Screenshots.** Add shots to `apps/roadwork/screenshots.sh` by passing `--env night-circuit` (name them `night-circuit-at-30s.png` and so on), run `SOLOTON_FORCE_XVFB=1 bash apps/roadwork/screenshots.sh "$PWD/build/shots"`, look at them, and commit them under `apps/roadwork/docs/`. The existing `scene-at-*.png` files are the riverfront and must not change when another environment is added; `picker.png` gains a card and is committed again. The script ignores `SOLOTON_MUSIC_DIR`, so committed screenshots show the built-in demo music, never anyone's own library.

6. **Tests.** `EnvironmentsTest` already checks every registry entry (unique kebab-case ids, titles, music folder is a plain folder name, scene constructible without OpenGL). Add unit tests for the new package's pure maths and geometry budgets next to it under `core/src/test/kotlin/dev/soloton/roadwork/env/nightcircuit/`, in the style of `env/riverfront/GeometryTest.kt`.

7. **Docs.** Add a row to the registry table above and to the folder table in [INSTALL.md](INSTALL.md#copying-music-to-the-tablet).

8. **Check.** `./gradlew check`, then on the gen 1 emulator: push the game's folder to `/sdcard/Music/soloton/`, start the app with `--es environment night-circuit`, and confirm the scene runs and only that folder's tracks play. Then start the app without the extra and check the new card on the picker.
