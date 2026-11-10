# Target device: Peloton Bike gen 1 tablet

Apps in this repo target the touchscreen on the original (non-plus) Peloton Bike, in its Android 7.0 era: tablet models PLTN-RB1VQ and PLTN-RB1VO, which a third-party guide describes as essentially the same.

## Specs we design for

| Spec | Value | Confidence |
|---|---|---|
| Android | 7.0, API 24 | Stated by the owner; matches the generation guide below |
| Screen | 21.5", 1920x1080, landscape, touch | Size and "HD" from the guide; 1080p from launch coverage of the bike |
| Pixel density | about 102 dpi physical. We assume Android reports **160 dpi (mdpi)** | Assumption: no source gives the reported density |
| Processor | MediaTek MT8173: 2x Cortex-A72 + 2x Cortex-A53, 64-bit ARM, 2.0 GHz | Third-party guide, not per model; unverified on this exact tablet |
| GPU | PowerVR GX6250, as on other MT8173 devices | Inferred from the SoC; unverified |
| RAM / storage | 2 GB / 16 GB | Third-party guide |
| Audio | 2 channels, 2x10 W speakers | Third-party guide |
| Google services | None | Peloton firmware ships without Play Services |

Sources: [Wellfit Insider, Peloton bike generations](https://wellfitinsider.com/peloton/peloton-bike-generations/), [Digital Trends](https://www.digitaltrends.com/mobile/peloton-bike-brings-studio-classes-into-the-home-with-android-tablet/) and [CNX Software](https://www.cnx-software.com/2013/06/26/android-now-comes-to-exercise-bikes-thanks-to-the-peloton-bike/) (launch-era hardware; the panel carried over). Peloton's own spec page could not be read (HTTP 401).

## What this means for code

- **API 24 only features.** No adaptive icons (fallback icon required), no FLAC decoding (added in 8.1), no Let's Encrypt root for HTTPS, runtime storage permission.
- **Graphics.** Target OpenGL ES 2.0 even though the GPU likely supports more. A 1080p fill rate on a mid-2015 tablet GPU is the main cost: avoid full-screen overdraw, post-processing, MSAA and per-pixel lighting. Budget per frame: under about 150 draw calls and about 60k triangles; the roadwork scene uses about 36 and 48k. Hold 30 fps or better.
- **Memory.** 2 GB shared with the Peloton system services. Keep an app under about 256 MB of heap plus textures; prefer vertex colours to textures.
- **ABIs.** Ship `arm64-v8a` and `armeabi-v7a`; `x86_64` is for the emulator only.
- **Touch and layout.** The screen is big and about arm's length away; at the assumed 160 dpi, 1 dp = 1 px, and 1 px is about 0.25 mm on this panel. Make touch targets at least 96 px (about 24 mm) and text at least 28 px.

## The gen 1 emulator

`scripts/gen1-emulator.sh` creates and starts an Android 7.0 emulator shaped like this tablet: API 24, 1920x1080 landscape, 160 dpi, 2 GB RAM, 4 cores. It uses an x86_64 system image because ARM images are too slow to emulate. CI's smoke test uses the same profile.

What the emulator cannot tell us: real GPU speed (it renders in software, so its frame rate is a rough lower bound for CPU work, not a GPU benchmark), real touch feel, speaker output, and Peloton system services.

```bash
scripts/gen1-emulator.sh            # create if needed, boot headless, wait for boot, apply display settings
scripts/gen1-emulator.sh --window   # same, with a window
scripts/gen1-emulator.sh --stop     # shut it down
```
