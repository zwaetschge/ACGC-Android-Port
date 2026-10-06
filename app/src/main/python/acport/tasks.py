"""Entry points called from the Android launcher (via Chaquopy).

Everything here works on files the user supplied; downloads (HD pack, Deluxe
patch) happen in the Java launcher.
"""
import argparse
import hashlib
import os
import shutil
import sys
from pathlib import Path

from .disc import Disc, tgc_files
from . import xdelta

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
    """msg is a step key (step_*) that the launcher maps to a localized string."""
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


def sha1_file(path):
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def build_deluxe(source, patch, out, expected_sha1, cb=None):
    """Apply an Animal Crossing Deluxe xdelta patch to the user's clean disc
    image and verify the result against the published output hash."""
    _say(cb, "step_acdx_patch")
    xdelta.apply(source, patch, out)
    _say(cb, "step_acdx_verify")
    got = sha1_file(out)
    if expected_sha1 and got != expected_sha1.upper():
        os.remove(out)
        raise ValueError(f"patched image SHA-1 {got} does not match {expected_sha1}")
    return got


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
            _say(cb, "step_nkit")
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

    _say(cb, "step_read_eur")
    eur = Disc(eur_disc)
    try:
        if not eur.game_id.startswith("GAFP01"):
            raise ValueError(f"European disc (GAFP01) required, got {eur.game_id}")
        tgc = eur.file_bytes(f"tgc/forest_{TGC_CODE[lang]}_Final_PAL50.tgc")
    finally:
        eur.close()
    for name, data in tgc_files(tgc).items():
        if name in ("forest_msg.arc", "forest_2nd.arc", "forest_1st_script.arc", "forestd.rel.szs",
                    "foresta.rel.szs", "foresta.map"):
            with open(os.path.join(eur_dir, name), "wb") as f:
                f.write(data)
    del tgc

    _say(cb, "step_read_us")
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

    _say(cb, "step_text")
    args = argparse.Namespace(
        eur_arc=os.path.join(eur_dir, "forest_msg.arc"),
        usa_arc=os.path.join(usa_dir, "forest_2nd.arc"),
        usa_1st_arc=os.path.join(usa_dir, "forest_1st.arc"),
        lang=lang, select_txt=None, out=None)
    l10n_flow.from_eur_flow(args)

    _say(cb, "step_msg")
    msg_dir = os.path.join(work_dir, "msg")
    os.makedirs(msg_dir)
    unpack_archive(os.path.join(eur_dir, "forest_msg.arc"), msg_dir)
    for root, _dirs, files in os.walk(msg_dir):
        if "msg.bin" in files:
            shutil.copy(os.path.join(root, "msg.bin"), os.path.join(out_dir, "msg.bin"))
            break
    else:
        raise RuntimeError("msg.bin not found in forest_msg.arc")

    _extract_code_strings(os.path.join(eur_dir, "foresta.rel.szs"), os.path.join(eur_dir, "foresta.map"),
                          os.path.join(out_dir, "code_strings.bin"))

    shutil.rmtree(work_dir, ignore_errors=True)
    _say(cb, "step_done")
    return out_dir


# UI strings that the USA build keeps as C literals (inventory tag menu), looked up
# by symbol name in the European foresta.rel. Tag words are 16-byte fields.
CODE_STRINGS = ("str_omikuji", "str_happy_room", "str_otodokemono", "str_otegami", "mTG_tag_str_suteruno",
                "mTG_tag_str_put_chk1", "mTG_tag_str_put_chk2")


def _extract_code_strings(rel_szs, map_path, out_path):
    """Write code_strings.bin: records of (u8 name_len, name, u8 len, bytes)."""
    import re
    import struct
    import l10n_flow

    rel = l10n_flow._yaz0_decompress(open(rel_szs, "rb").read())
    num, table = struct.unpack_from(">II", rel, 0x0C)
    offsets = [struct.unpack_from(">I", rel, table + 8 * i)[0] & ~1 for i in range(num)]
    # section order in the map matches the REL section indices (.text = 1)
    section_index = {".text": 1, ".ctors": 2, ".dtors": 3, ".rodata": 4, ".data": 5}
    symbols = {}
    section = None
    with open(map_path, encoding="ascii", errors="replace") as f:
        for line in f:
            m = re.match(r"^(\.\w+) section layout", line)
            if m:
                section = m.group(1)
                continue
            m = re.match(r"^\s+([0-9a-f]{8}) ([0-9a-f]{6}) [0-9a-f]{8}\s+\d+ (\S+)\s+m_tag_ovl\.o", line)
            if m and section in section_index:
                symbols.setdefault(m.group(3), (section, int(m.group(1), 16), int(m.group(2), 16)))

    records = []
    for name, (section, addr, size) in sorted(symbols.items()):
        if name.startswith("mTG_tag_word_"):
            size = 16
        elif name not in CODE_STRINGS:
            continue
        start = offsets[section_index[section]] + addr
        data = rel[start:start + min(size, 32)]
        records.append(struct.pack("B", len(name)) + name.encode() + struct.pack("B", len(data)) + data)
    with open(out_path, "wb") as f:
        f.write(b"".join(records))
    return len(records)
