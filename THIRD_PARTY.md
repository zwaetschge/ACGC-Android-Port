# Third-party components

| Component | License | How it is used |
|---|---|---|
| ac-decomp | CC0 1.0 | game source (`src/`, `include/`) |
| ACGC-PC-Port | MIT (see `LICENSE`) | PC port layer (`pc/`), base of this repository |
| FixNES | MIT (see `LICENSE`) | in-game NES emulator (`pc/lib/fixnes`) |
| birabittoh l10n branch | CC0 / MIT (as upstream) | EUR message loader (`pc/src/pc_msg_eur*`), translation tools (`app/src/main/python/acport/l10n/`) |
| pyjkernel | GPL-3.0 (`app/src/main/python/acport/l10n/pyjkernel/LICENSE`) | RARC archive reader/writer used by the translation tools; bundled in the APK, so APK distributions must follow GPL-3.0 (source is this repository) |
| SDL 2.30.12 | zlib | downloaded at build time; Java glue in `app/src/main/java/org/libsdl/app` (modified: IME without suggestions) |
| ARM astc-encoder 5.7.0 | Apache-2.0 | downloaded at build time; HD texture conversion |
| bcdec | MIT / Unlicense | downloaded at build time; BC1/BC3/BC7 decoding |
| Chaquopy 16.1 | MIT | Gradle plugin + Python 3.11 runtime in the APK |
| jcifs-ng 2.1.10 | LGPL-2.1 | SMB import (Maven dependency) |
| Animal Crossing HD Texture Pack | by its authors | **not redistributed**; the app downloads it from the link in its Dolphin forum thread on user request |

Game data (disc images, extracted files, text, textures) is never part of this repository or the APK.
