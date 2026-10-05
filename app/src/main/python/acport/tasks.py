"""Entry points called from the Android launcher (via Chaquopy).

Everything here works on files the user supplied; nothing is downloaded.
"""
import argparse
import os
import shutil
import sys
from pathlib import Path

from .disc import Disc, tgc_files

# Android: copying extended attributes (the SELinux label) fails with EACCES,
# which breaks shutil.copy2/copytree inside the l10n tools. Metadata is irrelevant here.
if hasattr(shutil, "_copyxattr"):
    shutil._copyxattr = lambda *args, **kwargs: None

L10N_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "l10n")
if L10N_DIR not in sys.path:
    sys.path.insert(0, L10N_DIR)

# EUR disc TGC per language profile (translations/<lang>/)
TGC_CODE = {"de-DE": "Gmn", "fr-FR": "Frn", "it-IT": "Itl", "es-ES": "Spn", "en-EU": "Eng"}


def _say(cb, msg):
    print(msg)
    if cb is not None:
        cb.onProgress(msg)


def disc_info(path):
    """'GAFE01|iso' style summary, or raises for non-GameCube files."""
    d = Disc(path)
    try:
        return f"{d.game_id}|{d.kind}"
    finally:
        d.close()


def import_us_disc(src, rom_dir, cb=None):
    """Store the user's USA disc (a temp copy at src) in rom_dir.

    ISO/GCM/CISO images are used as-is by the game; NKit is rebuilt into a
    plain ISO. Returns the path of the resulting image.
    """
    d = Disc(src)
    try:
        if not d.game_id.startswith("GAFE01"):
            raise ValueError(f"USA disc (GAFE01) required, got {d.game_id}")
        os.makedirs(rom_dir, exist_ok=True)
        for f in os.listdir(rom_dir):
            os.remove(os.path.join(rom_dir, f))
        out = os.path.join(rom_dir, "GAFE01.ciso" if d.kind == "ciso" else "GAFE01.iso")
        if d.kind == "nkit":
            _say(cb, "NKit → ISO …")
            d.write_compact_iso(out)
    finally:
        d.close()
    if os.path.exists(out):
        os.remove(src)
    else:
        shutil.move(src, out)
    return out


def generate_translation(us_disc, eur_disc, lang, files_dir, work_dir, cb=None):
    """Build translations/<lang>/ (msg.bin, localized archives, item names)
    from the user's EUR disc and USA disc."""
    import l10n_flow
    from arc_tool import unpack_archive

    if lang not in TGC_CODE:
        raise ValueError(f"unsupported language {lang}")
    shutil.rmtree(work_dir, ignore_errors=True)
    eur_dir = os.path.join(work_dir, "eur")
    usa_dir = os.path.join(work_dir, "usa")
    os.makedirs(eur_dir)
    os.makedirs(usa_dir)

    _say(cb, "EU-Disc lesen …")
    eur = Disc(eur_disc)
    try:
        if not eur.game_id.startswith("GAFP01"):
            raise ValueError(f"European disc (GAFP01) required, got {eur.game_id}")
        tgc = eur.file_bytes(f"tgc/forest_{TGC_CODE[lang]}_Final_PAL50.tgc")
    finally:
        eur.close()
    for name, data in tgc_files(tgc).items():
        if name in ("forest_msg.arc", "forest_2nd.arc", "forest_1st_script.arc", "forestd.rel.szs"):
            with open(os.path.join(eur_dir, name), "wb") as f:
                f.write(data)
    del tgc

    _say(cb, "US-Disc lesen …")
    us = Disc(us_disc)
    try:
        for name in ("forest_1st.arc", "forest_2nd.arc"):
            with open(os.path.join(usa_dir, name), "wb") as f:
                f.write(us.file_bytes(name))
    finally:
        us.close()

    out_dir = os.path.join(files_dir, "translations", lang)
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    l10n_flow.ROOT_DIR = Path(files_dir)

    _say(cb, "Texte übertragen (dauert etwas) …")
    args = argparse.Namespace(
        eur_arc=os.path.join(eur_dir, "forest_msg.arc"),
        usa_arc=os.path.join(usa_dir, "forest_2nd.arc"),
        usa_1st_arc=os.path.join(usa_dir, "forest_1st.arc"),
        lang=lang, select_txt=None, out=None)
    l10n_flow.from_eur_flow(args)

    _say(cb, "Nachrichten extrahieren …")
    msg_dir = os.path.join(work_dir, "msg")
    os.makedirs(msg_dir)
    unpack_archive(os.path.join(eur_dir, "forest_msg.arc"), msg_dir)
    for root, _dirs, files in os.walk(msg_dir):
        if "msg.bin" in files:
            shutil.copy(os.path.join(root, "msg.bin"), os.path.join(out_dir, "msg.bin"))
            break
    else:
        raise RuntimeError("msg.bin not found in forest_msg.arc")

    shutil.rmtree(work_dir, ignore_errors=True)
    _say(cb, "Fertig")
    return out_dir
