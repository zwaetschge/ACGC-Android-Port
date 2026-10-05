# Animal Crossing (GameCube) – Android Port

An Android port of [flyngmt/ACGC-PC-Port](https://github.com/flyngmt/ACGC-PC-Port), the native PC port of
Animal Crossing built on the [ac-decomp](https://github.com/ACreTeam/ac-decomp) decompilation.
The original game code runs natively on the device; GX is translated to OpenGL ES 3.

**This repository and the app contain no game data.** You need your own copy of the game.
HD textures and translations are fetched or generated **on the device, by you**, from their
original sources (see below).

| | |
|---|---|
| Game disc | Animal Crossing **USA** (GAFE01, Rev 0) – `.iso`, `.gcm`, `.ciso` or NKit (`.nkit.iso`) |
| Devices | Android 8+ with a **32-bit capable** ARM CPU (`armeabi-v7a`) and OpenGL ES 3. Devices that only run 64-bit apps (e.g. some recent handhelds) are not supported yet – the decomp's display-list code packs pointers into 32-bit words, like upstream. |
| HD textures (optional) | needs `GL_KHR_texture_compression_astc_ldr` (practically every current Android GPU) |

## Features

- Full game, Dolphin-compatible GCI saves (app-private storage)
- Fullscreen with hor+ widescreen (16:9, 16:10, …) and a widened UI (dialogs, choices, inventory, HUD)
- Gamepad, keyboard and an on-screen **touch overlay** (GameCube layout, hides when a controller is used)
- Outdoor C-stick camera and the QoL options of upstream 0.9.3 (bells to wallet, fast text, tree shaking with net/rod, …)
- **HD texture pack**: one-tap download of the community *Animal Crossing HD Texture Pack* and on-device BC7 → ASTC conversion
- **Translations** (German, French, Italian, Spanish, English-EU) generated from your European disc (GAFP01)

## Using the app

The launcher has three steps:

1. **Game data** – choose your USA disc image (file picker or SMB share). NKit images are rebuilt into a
   plain ISO on the device.
2. **HD textures (optional)** – *Download & install* fetches the pack (~146 MB) from the link published in the
   pack's [Dolphin forum thread](https://forums.dolphin-emu.org/Thread-animal-crossing-hd-texture-pack-version-23-feb-22nd-2026)
   and converts it (about 2 minutes on a recent phone, ~1.5 GB). If Google Drive refuses the download, get the
   ZIP from the thread yourself and use *Choose ZIP*.
3. **Language (optional)** – *Create translation* asks for the language and your **European** disc image
   (GAFP01, NKit works) and extracts that language's text, item names and villager names into the app.
   German additionally gets the pack's German UI textures (notice board, HUD, inventory labels) when HD
   textures are installed.

Data lives in the app's private storage (`files/rom`, `files/texture_pack`, `files/translations`,
`files/save`) and is removed when the app is uninstalled. Back up `files/save` first.

## Building

Requirements: Android SDK with NDK 27.0.12077973 and CMake 3.22.1, JDK 17. Internet access during the first build.

```sh
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

The `fetchThirdParty` task downloads SDL2 2.30.12, ARM astc-encoder 5.7.0 and bcdec into
`app/src/main/cpp/third_party/` (not committed). Chaquopy provides the Python runtime for the disc and
translation tools in `app/src/main/python/`.

### Layout

| Path | Content |
|---|---|
| `src/`, `include/`, `pc/` | decomp + PC port (upstream) with the Android changes (`TARGET_ANDROID`) |
| `app/` | Android app: launcher, SDL activity, touch overlay, CMake build |
| `app/src/main/cpp/texconv/` | BC7/BC1/BC3 → ASTC 4x4 converter (JNI) |
| `app/src/main/python/acport/` | disc reader (ISO/CISO/NKit), NKit → ISO, translation pipeline |
| `app/src/main/assets/l10n/de-DE_textures.json` | PAL→USA texture *hash* table for the German UI textures (no image data) |
| `tools/android/` | desktop scripts used to create the hash table / test conversions |
| `docs/README-PC-PORT.md` | the upstream PC port README |

## Credits

- [ACreTeam/ac-decomp](https://github.com/ACreTeam/ac-decomp) – decompilation (CC0)
- [flyngmt/ACGC-PC-Port](https://github.com/flyngmt/ACGC-PC-Port) – PC port (MIT), includes FixNES (MIT)
- [birabittoh/ACGC-PC-Port `l10n`](https://github.com/birabittoh/ACGC-PC-Port/tree/l10n) – EUR translation
  loader and tools (`pc/src/pc_msg_eur*`, `app/src/main/python/acport/l10n/`); pyjkernel is GPL-3.0
- *Animal Crossing HD Texture Pack* – TechieAndroid, Brackenhawk and the AC modding community (downloaded at runtime, not redistributed)
- SDL2 (zlib), ARM astc-encoder (Apache-2.0), bcdec (MIT/Unlicense), Chaquopy (MIT), jcifs-ng (LGPL-2.1)

See [THIRD_PARTY.md](THIRD_PARTY.md). Animal Crossing is © Nintendo. This project is not affiliated with Nintendo.
