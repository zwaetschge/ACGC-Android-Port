# Animal Crossing (GameCube) – Android Port

An Android port of [flyngmt/ACGC-PC-Port](https://github.com/flyngmt/ACGC-PC-Port), the native PC port of
Animal Crossing built on the [ac-decomp](https://github.com/ACreTeam/ac-decomp) decompilation.
The original game code runs natively on the device; GX is translated to OpenGL ES 3.

> Built with AI coding agents – **Z.ai (GLM)**, **OpenAI Codex** and **Claude** – under human supervision.
> See [How this port was made](#how-this-port-was-made).

**This repository and the app contain no game data.** You need your own copy of the game.
HD textures and translations are fetched or generated **on the device, by you**, from their
original sources (see below).

| | |
|---|---|
| Game disc | Animal Crossing **USA** (GAFE01, Rev 0) – `.iso`, `.gcm`, `.ciso` or NKit (`.nkit.iso`) |
| Devices | Android 8+, ARM CPU (`arm64-v8a` or `armeabi-v7a`) and OpenGL ES 3. One APK for both; 64-bit-only devices are supported since v0.4.0 (tested on an AYN Odin 3). |
| HD textures (optional) | needs `GL_KHR_texture_compression_astc_ldr` (practically every current Android GPU) |

## Download

Get the APK from [Releases](https://github.com/zwaetschge/ACGC-Android-Port/releases). It contains no game
data; the launcher walks you through importing your own disc.

## Features

- Full game, Dolphin-compatible GCI saves (app-private storage), 32- and 64-bit ARM
- Fullscreen with hor+ widescreen (16:9, 16:10, …) and a widened UI (dialogs, choices, inventory, HUD)
- Gamepad, keyboard and an on-screen **touch overlay** (GameCube layout, hides when a controller is used)
- Outdoor C-stick camera and the QoL options of upstream 0.9.3 (bells to wallet, fast text, tree shaking with net/rod, …)
- **HD texture pack**: one-tap download of the community *Animal Crossing HD Texture Pack* and on-device BC7 → ASTC conversion
- **Translations** (German, French, Italian, Spanish) generated from your European disc (GAFP01)
- Launcher in the system language (English, German, French, Italian, Spanish) with a language switch that also selects the game language
- **Play together**: visit a friend's town by code through a small server anyone can host ([tools/multiplayer-server](tools/multiplayer-server))
- Optional Deluxe-style settings in the in-game options (Android *Back* opens them): skip Tom Nook's part-time job, sync the clock to the device time
- **Animal Crossing Deluxe** (Cuyler36's mod) can be built from your disc and is played in Dolphin – it replaces the game code, so it cannot run on this port itself

## Using the app

The launcher has these sections:

1. **Game data** – choose your USA disc image (file picker or SMB share). NKit images are rebuilt into a
   plain ISO on the device.
2. **HD textures (optional)** – *Download & install* fetches the pack (~146 MB) from the link published in the
   pack's [Dolphin forum thread](https://forums.dolphin-emu.org/Thread-animal-crossing-hd-texture-pack-version-23-feb-22nd-2026)
   and converts it (about 2 minutes on a recent phone, ~1.5 GB). If Google Drive refuses the download, get the
   ZIP from the thread yourself and use *Choose ZIP*.
3. **Language (optional)** – the language chip (top right) selects the launcher and game language; it
   defaults to the system language. For German, French, Italian or Spanish, *Create from EU disc* asks for
   your **European** disc image (GAFP01, NKit works) and extracts that language's text, item names and
   villager names into the app. English is built in.
   German additionally gets the pack's German UI textures (notice board, HUD, inventory labels) when HD
   textures are installed.

4. **Play together (optional)** – the GameCube game's multiplayer is visiting a friend's town from memory
   card B. Instead of handing over a card, one player *Shares* their town and gets a short code; the friend
   enters it under *Visit a town*, takes the train in the game, and later *Sends the town back*. The owner then
   *Gets the town back*. This needs a server that one of you hosts – a single Python file or a Docker container,
   see [tools/multiplayer-server](tools/multiplayer-server/README.md).
5. **Animal Crossing Deluxe (optional)** – builds Cuyler36's [Deluxe](https://cuyler36.github.io/acdx/) image
   from your disc with the official patches and opens it in [Dolphin](https://dolphin-emu.org/).

Settings that the original game has no menu for are in the in-game options: press *Back* (or *Esc* on a
keyboard) while playing. *Gameplay* has **Nook's job** (skip the part-time job; a running job is finished on the
next load) and **Clock** (reset the in-game clock to the device time on every load).

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

### Release builds

`./gradlew assembleRelease` signs with the key from a `keystore.properties` in the project root
(`storePassword`, `keyPassword`, `keyAlias` and `storeFile` or `storeBase64`, PKCS12; never committed).
Without it the release APK is signed with the debug key.

## How this port was made

This Android port was developed with AI coding agents, supervised and tested on real hardware by a human:
**[Z.ai](https://z.ai) (GLM)**, **[OpenAI Codex](https://openai.com/codex)** and
**[Claude](https://claude.com/claude-code) (Anthropic)**. They did the porting work – GLES3 shell, input,
widescreen UI, touch controls, the BC7→ASTC converter, the on-device disc/translation tooling – and the
debugging on an Android tablet (e.g. the clock overflow, the dialogue wedge and the translation offset bug).
The decompilation and the PC port it builds on are the work of the people credited below.

## Credits

- [ACreTeam/ac-decomp](https://github.com/ACreTeam/ac-decomp) – decompilation (CC0)
- [flyngmt/ACGC-PC-Port](https://github.com/flyngmt/ACGC-PC-Port) – PC port (MIT), includes FixNES (MIT)
- [birabittoh/ACGC-PC-Port `l10n`](https://github.com/birabittoh/ACGC-PC-Port/tree/l10n) – EUR translation
  loader and tools (`pc/src/pc_msg_eur*`, `app/src/main/python/acport/l10n/`); pyjkernel is GPL-3.0
- 64-bit port groundwork by Marco Andronaco (birabittoh) and chasem-dev in
  [birabittoh/ACGC-PC-Port](https://github.com/birabittoh/ACGC-PC-Port); adapted here so that 32-bit builds stay
  unchanged (64-bit code paths sit behind `__SIZEOF_POINTER__ == 8`)
- *Animal Crossing HD Texture Pack* – TechieAndroid, Brackenhawk and the AC modding community (downloaded at runtime, not redistributed)
- SDL2 (zlib), ARM astc-encoder (Apache-2.0), bcdec (MIT/Unlicense), Chaquopy (MIT), jcifs-ng (LGPL-2.1)

See [THIRD_PARTY.md](THIRD_PARTY.md). Animal Crossing is © Nintendo. This project is not affiliated with Nintendo.
