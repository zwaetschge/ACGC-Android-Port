#!/usr/bin/env python3
"""l10n_flow.py - Localization workflow for the Animal Crossing GCN PC port.

Commands
--------
extract          Unpack forest_2nd.arc and dump USA message text for editing.
repack           Encode edited text and repack into a localized forest_2nd arc.
from-eur         Build localized forest_2nd + forest_1st arcs from an EUR TGC file
                 (NPC dialogue AND select strings are auto-translated from EUR disc).
dump-select      Dump the USA player-choice strings to a text file (English template).
dump-select-eur  Auto-populate a select text file from an EUR forest_1st_script.arc.

Quick start — build a French translation from the EUR disc
----------------------------------------------------------
    # 1. Extract the EUR TGC for French
    dtk disc extract orig/GAFP01_00/files/tgc/forest_Frn_Final_PAL50.tgc \\
        orig/GAFP01_00/tgc_Frn/

    # 2a. (Optional) Dump English choice strings as a translation template
    python -m tools.l10n_flow dump-select \\
        --usa-1st-arc orig/GAFE01_00/files/forest_1st.arc \\
        --out         text/select_Frn.txt
    #     Edit text/select_Frn.txt, then pass it with --select-txt below.

    # 2b. Generate both translation arcs in one step
    python -m tools.l10n_flow from-eur \\
        --eur-arc     orig/GAFP01_00/tgc_Frn/files/forest_msg.arc \\
        --usa-arc     orig/GAFE01_00/files/forest_2nd.arc \\
        --usa-1st-arc orig/GAFE01_00/files/forest_1st.arc \\
        --lang        fr-FR \\
        --select-txt  text/select_Frn.txt   # omit to keep English choice strings

    # 3. Set  language = fr-FR  in settings.ini and launch the game.

Supported EUR language tags (matching TGC filenames)
-----------------------------------------------------
    Eng -> en-EU    Frn -> fr-FR    Gmn -> de-DE
    Itl -> it-IT    Spn -> es-ES
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

META_FILE_NAME = "l10n_meta.json"

# ── NPC name translation tables ───────────────────────────────────────────────
# Extracted from EUR forest_1st_script.arc → string.bin (verified against live data).
# Order matches USA string_data.bin entries 516-535 (Tom Nook=0x204 .. Tommy=0x217).
_NPC_NAME_USA = [
    "Tom Nook", "Redd", "Katrina", "Saharah", "Wendell", "Jingle",
    "Gracie", "Joan", "Pelly", "Phyllis", "Pete", "Copper", "Porter",
    "Jack", "Gyroid", "K.K.", "Rover", "Chip", "Timmy", "Tommy",
]
_NPC_NAME_INDEX_START = 0x205  # first string_data.bin index holding a NPC name (Tom Nook)

# Blathers, Mabel, Sable live at separate indices (0x4e3-0x4e5) outside the main block.
_NPC_EXTRA_USA  = ["Blathers", "Mabel", "Sable"]
_NPC_EXTRA_INDEX_START = 0x4e4  # Blathers, Mabel, Sable

_EUR_NPC_NAMES: dict[str, list[str]] = {
    "it-IT": [
        "Tom Nook", "Volpolo", "Vanda", "Sahara", "Van Trik", "Jingle",
        "Griffa", "Nella", "Pelly", "Polly", "Tino", "Birro", "Ciufciuf",
        "Fifonio", "Giroide", "K.K.", "Girolamo", "Castore", "Mirco", "Marco",
    ],
    "fr-FR": [
        "Tom Nook", "Rounard", "Astrid", "Sarah", "Morsicus", "Rodolphe",
        "Carla", "Porcella", "Op\u00e9lie", "Elisabec", "Antoine", "Maret",
        "Lazare", "Jacqu\u0027O", "Gyro\u00efde", "K\u00e9k\u00e9",
        "Charly", "Castor", "M\u00e9li", "M\u00e9lo",
    ],
    "de-DE": [
        "Tom Nook", "Reiner", "Smeralda", "Aziza", "Winci", "Chris",
        "Grazia", "Siegrid", "Pelly", "Peggy", "Peter", "Harry", "Flip",
        "Jakob", "Gyroid", "K.K.", "Olli", "Bartholo", "Nepp", "Schlepp",
    ],
    "es-ES": [
        "Tom Nook", "Ladino", "Katrina", "Alcatifa", "Da Morsi", "Renato",
        "Graciela", "Juana", "Sol", "Estrella", "Carturo", "Vigilio",
        "Estasio", "Soponcio", "Giroide", "Totakeke", "Fran", "Mart\u00edn",
        "Tendo", "Nendo",
    ],
    # en-EU intentionally omitted: keep USA English names as-is
}

# Blathers/Mabel/Sable translated names at indices 0x4e3-0x4e5.
_EUR_NPC_EXTRA: dict[str, list[str]] = {
    "it-IT": ["Blatero", "Agostina", "Filomena"],
    "fr-FR": ["Thibou",  "Isabelle",  "Coco"],
    "de-DE": ["Eugen",   "Nähchen",  "Mähchen"],
    "es-ES": ["Fili",    "Isabela",   "Costura"],
}

_EUR_MONTHS: dict[str, list[str]] = {
    "it-IT": ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
              "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"],
    "fr-FR": ["janvier", "f\u00e9vrier", "mars", "avril", "mai", "juin",
              "juillet", "ao\u00fbt", "septembre", "octobre", "novembre", "d\u00e9cembre"],
    "de-DE": ["Januar", "Februar", "M\u00e4rz", "April", "Mai", "Juni",
              "Juli", "August", "September", "Oktober", "November", "Dezember"],
    "es-ES": ["enero", "febrero", "marzo", "abril", "mayo", "junio",
              "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"],
    # en-EU intentionally omitted: keep USA English months
}
_EUR_DAYS: dict[str, list[str]] = {
    lang: [str(n) for n in range(1, 32)]
    for lang in ("it-IT", "fr-FR", "de-DE", "es-ES")
    # en-EU intentionally omitted: keep USA English ordinals
}
_DAY_INDEX_START   = 0x64E   # mString_DAY_START   (include/m_string_data.h)
_DAY_INDEX_COUNT   = 31
_MONTH_INDEX_START = 0x66D   # mString_MONTH_START  (include/m_string_data.h)
_MONTH_INDEX_COUNT = 12

TOOLS_DIR = Path(__file__).resolve().parent
ROOT_DIR = TOOLS_DIR.parent

# ── Item / furniture name extraction from EUR forestd.rel.szs ─────────────────
# ftrName_table start offset in EUR forestd.rel.szs — identical for all 5 EUR
# language TGCs (Eng/Frn/Gmn/Itl/Spn), verified by inspection.
_EUR_FORESTD_FTR_OFFSET = 0x1E37D0
# ftrName2_table is always 0x4000 bytes after ftrName_table in all EUR TGCs.
_EUR_FORESTD_FTR2_OFFSET = _EUR_FORESTD_FTR_OFFSET + 0x4000

# Item-name section layout tables for EUR forestd.rel.szs.
#
# Each entry: (filename, ftr_dist, eur_size, usa_size)
#   ftr_dist  — section_start = _EUR_FORESTD_FTR_OFFSET - ftr_dist
#   eur_size  — bytes available in the EUR binary for this section
#   usa_size  — size of the destination array in the PC port (from pc_assets.c)
#
# When eur_size < usa_size the extracted block is zero-padded to usa_size so
# the game falls back to the built-in English name for any untranslated entries.
# When eur_size > usa_size only the first usa_size bytes are used.
#
# Offsets verified empirically against all five EUR language TGC archives.

# Tuple format: (filename, ftr_dist, eur_size, usa_size, usa_skip)
#   ftr_dist: offset from _EUR_FORESTD_FTR_OFFSET to the start of the EUR data.
#             None → write a zero-filled file (section has no direct EUR source).
#   usa_skip: unused in the main loop for non-ENG (kept as 0 for all entries);
#             used by the ENG layout if ever needed.

# en-EU: section order and sizes match the USA foresta.rel exactly.
_EUR_LAYOUT_ENG: list[tuple[str, int, int, int, int]] = [
    ("itemName_paper.bin",      0x42B0, 0x1000, 0x1000, 0),
    ("itemName_money.bin",      0x32B0, 0x0040, 0x0040, 0),
    ("itemName_tool.bin",       0x3270, 0x05C0, 0x05C0, 0),
    ("itemName_fish.bin",       0x2CB0, 0x0280, 0x0280, 0),
    ("itemName_cloth.bin",      0x2A30, 0x0FF0, 0x0FF0, 0),
    ("itemName_etc.bin",        0x1A40, 0x0310, 0x0310, 0),
    ("itemName_carpet.bin",     0x1730, 0x0430, 0x0430, 0),
    ("itemName_wall.bin",       0x1300, 0x0430, 0x0430, 0),
    ("itemName_fruit.bin",      0x0ED0, 0x0080, 0x0080, 0),
    ("itemName_plant.bin",      0x0E50, 0x00B0, 0x00B0, 0),
    ("itemName_minidisk.bin",   0x0DA0, 0x0370, 0x0370, 0),
    ("itemName_dummy.bin",      0x0A30, 0x0100, 0x0100, 0),
    ("itemName_ticket.bin",     0x0930, 0x0600, 0x0600, 0),
    ("itemName_insect.bin",     0x0330, 0x02D0, 0x02D0, 0),
    ("itemName_hukubukuro.bin", 0x0060, 0x0020, 0x0020, 0),
    ("itemName_kabu.bin",       0x0040, 0x0040, 0x0040, 0),
    ("ftrName_table.bin",       0x0000, 0x4000, 0x4000, 0),
]

# fr-FR / de-DE / it-IT / es-ES: the compiled REL uses different ftr_dist values
# for tool, fish, cloth (each shifted +0x40 = 4 entries earlier relative to ENG).
# The sections for carpet and wall are assembled from multiple EUR sources by
# _extract_item_names post-processing (see below).
#
# Mapping of EUR sections to USA item-name arrays:
#
#   EUR tool   (ftr_dist 0x32B0, 92 entries)  → USA tool[0-91]
#              entry 0 = retino (net), entry 1 = ascia (axe), …
#   EUR fish   (ftr_dist 0x2CF0, 40 entries)  → USA fish[0-39]
#              entry 0 = carpa cruciana, entry 4 = pesce gatto (catfish), …
#   EUR cloth  (ftr_dist 0x2A70, 255 entries) → USA cloth[0-254]
#
#   EUR "etc"  (ftr_dist 0x1A80, 53 entries)  → USA carpet[0-52]
#              (the EUR section named "etc" contains carpet items in non-ENG)
#   EUR carpet (ftr_dist 0x1730, 67 entries)
#              entries  0-13 → USA carpet[53-66]
#              entries 14-66 → USA wall[0-52]
#   EUR wall   (ftr_dist 0x1300, 14 entries)  → USA wall[53-66]
_EUR_LAYOUT_NON_ENG: list[tuple[str, int, int, int, int]] = [
    ("itemName_paper.bin",      0x4270, 0x1000, 0x1000, 0),
    ("itemName_tool.bin",       0x32B0, 0x05C0, 0x05C0, 0),  # +0x40 vs ENG
    ("itemName_fish.bin",       0x2CF0, 0x0280, 0x0280, 0),  # +0x40 vs ENG
    ("itemName_cloth.bin",      0x2A70, 0x0FF0, 0x0FF0, 0),  # +0x40 vs ENG
    # itemName_etc.bin: EUR "etc" is carpet items; carpet/wall rebuilt in post-processing.
    ("itemName_etc.bin",        None,   0x0000, 0x0310, 0),
    ("itemName_carpet.bin",     None,   0x0000, 0x0430, 0),
    ("itemName_wall.bin",       None,   0x0000, 0x0430, 0),
    # EUR fruit has 10 entries; USA has 8.  Use only the first 8.
    ("itemName_fruit.bin",      0x1220, 0x0080, 0x0080, 0),
    # EUR plant has 9 entries; USA has 11.  Pad the last 2 with zeros.
    ("itemName_plant.bin",      0x1180, 0x0090, 0x00B0, 0),
    # EUR minidisk has 71 entries; first 55 are K.K. songs matching USA.
    ("itemName_minidisk.bin",   0x10F0, 0x0370, 0x0370, 0),
    ("itemName_insect.bin",     0x0C80, 0x02D0, 0x02D0, 0),
    # money is placed after insect in non-English EUR (not between paper and tool).
    ("itemName_money.bin",      0x09B0, 0x0040, 0x0040, 0),
    # EUR dummy has 48 entries; USA has 16.  Use only the first 16.
    ("itemName_dummy.bin",      0x0970, 0x0100, 0x0100, 0),
    ("itemName_ticket.bin",     0x0660, 0x0600, 0x0600, 0),
    ("itemName_hukubukuro.bin", 0x0060, 0x0020, 0x0020, 0),
    ("itemName_kabu.bin",       0x0040, 0x0040, 0x0040, 0),
    ("ftrName_table.bin",       0x0000, 0x4000, 0x4000, 0),
]


def _yaz0_decompress(data: bytes) -> bytes:
    """Decompress a Yaz0/SZS stream (Nintendo GameCube format)."""
    import struct
    if data[:4] != b'Yaz0':
        raise ValueError("Not a Yaz0 stream")
    dec_size = struct.unpack_from('>I', data, 4)[0]
    out = bytearray(dec_size)
    src, dst = 16, 0
    while dst < dec_size:
        if src >= len(data):
            break
        code = data[src]; src += 1
        for _ in range(8):
            if dst >= dec_size:
                break
            if code & 0x80:
                out[dst] = data[src]; src += 1; dst += 1
            else:
                b1 = data[src]; b2 = data[src + 1]; src += 2
                dist = ((b1 & 0x0F) << 8) | b2
                copy_src = dst - dist - 1
                n = (b1 >> 4) + 2
                if n == 2:
                    n = data[src] + 18; src += 1
                for _ in range(n):
                    out[dst] = out[copy_src]; copy_src += 1; dst += 1
            code <<= 1
    return bytes(out)


def _extract_item_names(eur_forestd_szs: Path, out_dir: Path, lang: str = "en-EU") -> int:
    """Extract translated item/furniture name bins from EUR forestd.rel.szs.

    Selects the correct section layout table for the given language, then for
    each section copies the EUR bytes into a zero-padded buffer sized to match
    the USA destination array and writes it to out_dir/assets/<name>.bin.
    Also extracts ftrName2_table.bin (always at a fixed offset).

    Returns the number of files written.
    """
    dec = _yaz0_decompress(eur_forestd_szs.read_bytes())

    layout = _EUR_LAYOUT_ENG if lang == "en-EU" else _EUR_LAYOUT_NON_ENG

    out_assets = out_dir / "assets"
    out_assets.mkdir(parents=True, exist_ok=True)

    _ES = 16  # each item name occupies 16 bytes (null-padded)

    def _eur_off(ftr_dist: int) -> int:
        return _EUR_FORESTD_FTR_OFFSET - ftr_dist

    def _copy_entries(buf: bytearray, buf_slot: int,
                      src_off: int, src_entry: int, count: int) -> None:
        """Copy `count` 16-byte entries from dec[src_off + src_entry*16 ...] into buf."""
        for i in range(count):
            b = src_off + (src_entry + i) * _ES
            buf[(buf_slot + i) * _ES: (buf_slot + i + 1) * _ES] = dec[b: b + _ES]

    written = 0
    for filename, ftr_dist, eur_size, usa_size, usa_skip in layout:
        buf = bytearray(usa_size)          # zero-filled to usa_size
        if ftr_dist is not None:
            skip_bytes = usa_skip * _ES
            copy_len = min(eur_size, usa_size - skip_bytes)
            buf[skip_bytes: skip_bytes + copy_len] = dec[_eur_off(ftr_dist): _eur_off(ftr_dist) + copy_len]
        (out_assets / filename).write_bytes(bytes(buf))
        written += 1

    # ── Non-English post-processing: rebuild carpet and wall ─────────────────
    # In non-ENG the EUR REL names its sections differently from USA.  After the
    # main loop writes tool/fish/cloth directly (using their corrected ftr_dists),
    # carpet and wall must be assembled from multiple EUR source sections:
    #
    #   EUR "etc"    (ftr_dist 0x1A80, 53 entries) → USA carpet[0-52]
    #   EUR "carpet" (ftr_dist 0x1730, entries 0-13)  → USA carpet[53-66]
    #   EUR "carpet" (ftr_dist 0x1730, entries 14-66) → USA wall[0-52]
    #   EUR "wall"   (ftr_dist 0x1300, entries 0-13)  → USA wall[53-66]
    if lang != "en-EU":
        def _find(name: str) -> tuple:
            return next(t for t in layout if t[0] == name)

        etc_off  = _EUR_FORESTD_FTR_OFFSET - 0x1A80   # corrected ftr_dist for non-ENG
        carp_off = _EUR_FORESTD_FTR_OFFSET - 0x1730
        wall_off = _EUR_FORESTD_FTR_OFFSET - 0x1300

        # carpet: slots 0-52 from EUR "etc", slots 53-66 from EUR "carpet"[0-13]
        carpet_usa_size = _find("itemName_carpet.bin")[3]
        carpet_buf = bytearray(carpet_usa_size)
        _copy_entries(carpet_buf,  0, etc_off,   0, 53)
        _copy_entries(carpet_buf, 53, carp_off,  0, 14)
        (out_assets / "itemName_carpet.bin").write_bytes(bytes(carpet_buf))

        # wall: slots 0-52 from EUR "carpet"[14-66], slots 53-66 from EUR "wall"[0-13]
        wall_usa_size = _find("itemName_wall.bin")[3]
        wall_buf = bytearray(wall_usa_size)
        _copy_entries(wall_buf,  0, carp_off, 14, 53)
        _copy_entries(wall_buf, 53, wall_off,  0, 14)
        (out_assets / "itemName_wall.bin").write_bytes(bytes(wall_buf))

    # ftrName2_table is always immediately after ftrName_table (fixed for all langs)
    ftr2_size = 0x0F20
    buf = bytearray(ftr2_size)
    buf[:ftr2_size] = dec[_EUR_FORESTD_FTR2_OFFSET: _EUR_FORESTD_FTR2_OFFSET + ftr2_size]
    (out_assets / "ftrName2_table.bin").write_bytes(bytes(buf))
    written += 1

    return written


def run(cmd, cwd=None):
    subprocess.run(cmd, cwd=cwd, check=True)


# ── Helpers shared with bmg_to_msg ───────────────────────────────────────────

def _extract_arc(arc_path: str, out_dir: str) -> str:
    """Extract a JKR archive; return path to the extracted root directory."""
    sys.path.insert(0, str(TOOLS_DIR))
    from arc_tool import unpack_archive

    os.makedirs(out_dir, exist_ok=True)
    unpack_archive(arc_path, out_dir)
    # arc_tool creates a subdirectory named after the archive root
    entries = [e for e in os.listdir(out_dir) if os.path.isdir(os.path.join(out_dir, e))]
    if not entries:
        raise RuntimeError(f"No directory found after extracting {arc_path} into {out_dir}")
    return os.path.join(out_dir, entries[0])


def _pack_arc(src_root: str, out_arc: str):
    sys.path.insert(0, str(TOOLS_DIR))
    from arc_tool import pack_archive

    os.makedirs(os.path.dirname(os.path.abspath(out_arc)), exist_ok=True)
    pack_archive(src_root, out_arc)


def _find_file(base: str, filename: str) -> str | None:
    for root, _, files in os.walk(base):
        if filename in files:
            return os.path.join(root, filename)
    return None


def parse_localized_archive_name(arc_path: Path):
    stem = arc_path.stem
    if "." not in stem:
        return stem, None
    base_name, language = stem.rsplit(".", 1)
    if not base_name or not language:
        return stem, None
    return base_name, language


# ── extract ───────────────────────────────────────────────────────────────────

def find_message_files(unpack_dir: Path):
    data_candidates = sorted(unpack_dir.rglob("message_data.bin"))
    if not data_candidates:
        raise FileNotFoundError(
            "Could not find message_data.bin in unpacked archive. "
            "For this PC port, message resources are in forest_2nd.arc."
        )
    for data_path in data_candidates:
        table_path = data_path.with_name("message_data_table.bin")
        if table_path.exists():
            return data_path, table_path
    raise FileNotFoundError("Found message_data.bin but message_data_table.bin was not found")


def get_archive_root(unpack_dir: Path, msg_data_path: Path) -> Path:
    rel = msg_data_path.relative_to(unpack_dir)
    if len(rel.parts) == 0:
        raise RuntimeError("Unexpected archive layout")
    return unpack_dir / rel.parts[0]


def extract_flow(args):
    arc_tool = TOOLS_DIR / "arc_tool.py"
    msg_tool = TOOLS_DIR / "msg_tool.py"

    source_arc = Path(args.arc).resolve()
    if not source_arc.exists():
        raise FileNotFoundError(f"Source archive not found: {source_arc}")

    work_dir = Path(args.workdir).resolve()
    unpack_dir = work_dir / "unpacked"
    text_out = Path(args.text).resolve() if args.text else work_dir / "message_dump.txt"
    meta_path = work_dir / META_FILE_NAME

    if unpack_dir.exists() and not args.force:
        raise FileExistsError(f"{unpack_dir} exists. Use --force to overwrite")
    if unpack_dir.exists() and args.force:
        shutil.rmtree(unpack_dir)

    work_dir.mkdir(parents=True, exist_ok=True)
    unpack_dir.mkdir(parents=True, exist_ok=True)

    run([sys.executable, str(arc_tool), str(source_arc), str(unpack_dir)])

    msg_data_path, msg_table_path = find_message_files(unpack_dir)
    archive_root = get_archive_root(unpack_dir, msg_data_path)

    run([sys.executable, str(msg_tool), "-m", "unpack", str(msg_data_path), str(text_out)])

    meta = {
        "source_arc": str(source_arc),
        "work_dir": str(work_dir),
        "unpack_dir": str(unpack_dir),
        "archive_root": str(archive_root),
        "message_data_path": str(msg_data_path),
        "message_table_path": str(msg_table_path),
        "text_dump_path": str(text_out),
        "message_data_size": msg_data_path.stat().st_size,
        "message_table_size": msg_table_path.stat().st_size,
    }
    meta_path.write_text(json.dumps(meta, indent=2), encoding="utf-8")

    print(f"Extracted: {source_arc}")
    print(f"Text dump: {text_out}")
    print(f"Metadata : {meta_path}")


# ── repack ────────────────────────────────────────────────────────────────────

def repack_flow(args):
    arc_tool = TOOLS_DIR / "arc_tool.py"
    msg_tool = TOOLS_DIR / "msg_tool.py"

    work_dir = Path(args.workdir).resolve()
    meta_path = work_dir / META_FILE_NAME

    if not meta_path.exists():
        raise FileNotFoundError(f"{meta_path} not found. Run the extract step first.")

    meta = json.loads(meta_path.read_text(encoding="utf-8"))

    source_arc = Path(meta["source_arc"])
    archive_root = Path(meta["archive_root"])
    msg_data_path = Path(meta["message_data_path"])
    text_dump_path = Path(args.text).resolve() if args.text else Path(meta["text_dump_path"])

    if not text_dump_path.exists():
        raise FileNotFoundError(f"Text file not found: {text_dump_path}")
    if not archive_root.exists():
        raise FileNotFoundError(f"Unpacked archive root not found: {archive_root}")
    if not msg_data_path.exists():
        raise FileNotFoundError(f"message_data.bin not found: {msg_data_path}")

    run([
        sys.executable, str(msg_tool), "-m", "pack",
        str(text_dump_path), str(msg_data_path),
        "--data_size", hex(int(meta["message_data_size"])),
        "--table_size", hex(int(meta["message_table_size"])),
    ])

    if args.out:
        out_arc = Path(args.out).resolve()
    else:
        base_name, detected_language = parse_localized_archive_name(source_arc)
        language = args.language or detected_language
        if not language:
            raise ValueError(
                "Could not infer language. Use --language (e.g. pt-BR) or --out."
            )
        out_arc = ROOT_DIR / "translations" / language / f"{base_name}{source_arc.suffix}"

    out_arc.parent.mkdir(parents=True, exist_ok=True)
    run([sys.executable, str(arc_tool), str(archive_root), str(out_arc)])
    print(f"Built localized archive: {out_arc}")

    if args.replace_source:
        backup = source_arc.with_suffix(source_arc.suffix + ".bak")
        shutil.copy2(source_arc, backup)
        shutil.copy2(out_arc, source_arc)
        print(f"Backed up original to  : {backup}")
        print(f"Replaced source archive: {source_arc}")


# ── from-eur helpers ──────────────────────────────────────────────────────────

def _build_forest_2nd(eur_arc_path: Path, usa_arc_path: Path, lang: str,
                      out_arc: Path, tmp: str) -> None:
    """Merge EUR text into USA message structure and pack forest_2nd.<lang>.arc."""
    from bmg_to_msg import convert_message_data

    # Extract EUR arc → find msg.bin
    print("\nExtracting EUR forest_msg.arc...")
    eur_root = _extract_arc(str(eur_arc_path), os.path.join(tmp, "eur"))
    eur_bin = _find_file(eur_root, "msg.bin")
    if not eur_bin:
        raise FileNotFoundError("msg.bin not found in EUR arc")

    # Extract USA arc → find message_data.bin / message_data_table.bin
    print("Extracting USA forest_2nd.arc...")
    usa_root = _extract_arc(str(usa_arc_path), os.path.join(tmp, "usa"))
    usa_data = _find_file(usa_root, "message_data.bin")
    usa_table = _find_file(usa_root, "message_data_table.bin")
    if not usa_data or not usa_table:
        raise FileNotFoundError(
            "message_data.bin or message_data_table.bin not found in USA arc"
        )

    # Merge EUR text into USA control-code structure
    print("Merging EUR text into USA control code structure...")
    with open(eur_bin, "rb") as f:
        eur_bmg = f.read()
    with open(usa_data, "rb") as f:
        usa_msg = f.read()
    with open(usa_table, "rb") as f:
        usa_tbl = f.read()

    new_data, new_table = convert_message_data(eur_bmg, usa_msg, usa_tbl)

    # Write merged data back into a copy of the USA unpacked tree, preserving
    # the archive root directory name ("bin2") so the repacked arc has the
    # correct layout.  A flat layout causes the game to crash (NULL deref).
    pack_arc_root = os.path.join(tmp, "pack2", os.path.basename(usa_root))
    shutil.copytree(usa_root, pack_arc_root)

    data_dst = _find_file(pack_arc_root, "message_data.bin")
    table_dst = _find_file(pack_arc_root, "message_data_table.bin")
    with open(data_dst, "wb") as f:
        f.write(new_data)
    with open(table_dst, "wb") as f:
        f.write(new_table)

    # Patch villager names from the EUR forest_2nd.arc (same dir as forest_msg.arc),
    # unless this is en-EU (EUR English names are identical to USA English).
    if lang != "en-EU":
        eur_2nd = eur_arc_path.parent / "forest_2nd.arc"
        if eur_2nd.exists():
            eur_2nd_root = _extract_arc(str(eur_2nd), os.path.join(tmp, "eur2nd"))
            eur_npc_names = _find_file(eur_2nd_root, "npc_name_str_table.bin")
            dst_npc_names = _find_file(pack_arc_root, "npc_name_str_table.bin")
            if eur_npc_names and dst_npc_names:
                shutil.copy2(eur_npc_names, dst_npc_names)
                print(f"  Patched villager names from EUR forest_2nd.arc.")
            else:
                print("  Warning: npc_name_str_table.bin not found — skipping villager names.")
        else:
            print(f"  Warning: EUR forest_2nd.arc not found at {eur_2nd} — skipping villager names.")

    print(f"\nPacking forest_2nd.{lang}.arc...")
    _pack_arc(pack_arc_root, str(out_arc))
    print(f"  → {out_arc}")


def _translate_npc_names(pack_arc_root: str, lang: str) -> None:
    """Patch string_data.bin in pack_arc_root with translated NPC names for lang.

    Replaces entries _NPC_NAME_INDEX_START .. _NPC_NAME_INDEX_START+19 with the
    EUR localised names from _EUR_NPC_NAMES.  No-ops for en-EU (names unchanged).
    """
    if lang not in _EUR_NPC_NAMES:
        return  # en-EU or unknown — keep USA names

    sys.path.insert(0, str(TOOLS_DIR))
    from select_tool import encode_ac_str
    import struct

    data_path  = _find_file(pack_arc_root, "string_data.bin")
    table_path = _find_file(pack_arc_root, "string_data_table.bin")
    if not data_path or not table_path:
        print("  Warning: string_data.bin not found — skipping NPC name translation.")
        return

    data_bytes  = Path(data_path).read_bytes()
    table_bytes = Path(table_path).read_bytes()
    orig_data_size  = len(data_bytes)
    orig_table_size = len(table_bytes)

    # USA format (mString_Load_StringFromRom): table[i] is the END offset of
    # entry i; entry i spans data[table[i-1] (0 for i == 0) : table[i]].
    n_entries = len(table_bytes) // 4
    if n_entries < 1:
        print("  Warning: string_data_table.bin too small — skipping NPC name translation.")
        return

    # Read all entries verbatim from the original data+table.
    entries: list[bytes] = []
    for i in range(n_entries):
        start_off = struct.unpack_from(">I", table_bytes, (i - 1) * 4)[0] if i > 0 else 0
        end_off   = struct.unpack_from(">I", table_bytes, i * 4)[0]
        entries.append(data_bytes[start_off:end_off])

    # Substitute translated NPC names (main block: Tom Nook..Tommy at 0x204-0x217).
    translated = _EUR_NPC_NAMES[lang]
    name_start = _NPC_NAME_INDEX_START
    for i, name in enumerate(translated):
        idx = name_start + i
        if idx >= n_entries:
            break
        entries[idx] = encode_ac_str(name)

    # Extra block: Blathers/Mabel/Sable at 0x4e3-0x4e5.
    if lang in _EUR_NPC_EXTRA:
        for i, name in enumerate(_EUR_NPC_EXTRA[lang]):
            idx = _NPC_EXTRA_INDEX_START + i
            if idx < n_entries:
                entries[idx] = encode_ac_str(name)

    # Substitute localised day strings (plain cardinals, no English ordinal suffix).
    if lang in _EUR_DAYS:
        for i, day_str in enumerate(_EUR_DAYS[lang]):
            idx = _DAY_INDEX_START + i
            if idx < n_entries:
                entries[idx] = encode_ac_str(day_str)

    # Substitute localised month names.
    if lang in _EUR_MONTHS:
        for i, month_str in enumerate(_EUR_MONTHS[lang]):
            idx = _MONTH_INDEX_START + i
            if idx < n_entries:
                entries[idx] = encode_ac_str(month_str)

    # Re-encode preserving ALL entries.
    new_data  = bytearray()
    new_table = bytearray(orig_table_size)  # keep original table file size
    for i in range(n_entries):
        new_data.extend(entries[i])
        struct.pack_into(">I", new_table, i * 4, len(new_data))  # end offset

    if len(new_data) < orig_data_size:
        new_data.extend(b"\x00" * (orig_data_size - len(new_data)))

    # Sanity: month/day ROM entries must not be zero-length.
    for check_idx, label in ((0x64E, "DAY_START"), (0x66D, "MONTH_START")):
        if check_idx + 1 < len(new_table) // 4:
            s = struct.unpack_from(">I", new_table, (check_idx - 1) * 4)[0]
            e = struct.unpack_from(">I", new_table, check_idx * 4)[0]
            if e <= s:
                print(f"  Warning: string_data[{check_idx:#x}] ({label}) is empty after repack!")

    Path(data_path).write_bytes(bytes(new_data))
    Path(table_path).write_bytes(bytes(new_table))

    changed_npc = sum(1 for i, n in enumerate(translated) if n != _NPC_NAME_USA[i])
    changed_npc += len(_EUR_NPC_EXTRA.get(lang, []))
    changed_days   = len(_EUR_DAYS.get(lang, []))
    changed_months = len(_EUR_MONTHS.get(lang, []))
    print(f"  Translated {changed_npc} NPC names, {changed_days} days, {changed_months} months for {lang}"
          f" ({n_entries} total string_data entries preserved).")


def _extract_eur_select_strings(eur_1st_script_arc: Path, tmp: str) -> list[str]:
    """Extract player-choice strings from EUR forest_1st_script.arc → list[str].

    The EUR arc contains select.bin in BMG format with 607 entries that map
    1:1 to the USA select_data.bin entries.
    """
    from bmg_to_msg import parse_bmg
    from msg_tool import CHAR_MAP

    eur_root = _extract_arc(str(eur_1st_script_arc), os.path.join(tmp, "eur1s"))
    sel_bin = _find_file(eur_root, "select.bin")
    if not sel_bin:
        raise FileNotFoundError("select.bin not found in EUR forest_1st_script.arc")

    data = Path(sel_bin).read_bytes()
    offsets, dat1 = parse_bmg(data)

    strings: list[str] = []
    for i in range(len(offsets)):
        start = offsets[i]
        end = offsets[i + 1] if i + 1 < len(offsets) else len(dat1)
        raw = dat1[start:end]
        # Decode: plain AC charset bytes; skip EUR escape sequences (0x80)
        chars: list[str] = []
        j = 0
        while j < len(raw):
            b = raw[j]
            if b == 0x80:
                size = raw[j + 1] if j + 1 < len(raw) else 2
                j += max(size, 2)
            elif b == 0x00:
                # AC charset maps 0x00 to '¡' (Spanish opening '!').  A NUL is
                # only a terminator as trailing padding at the end of the
                # slice; anywhere else it is real text.
                if not any(x != 0 for x in raw[j:]):
                    break
                chars.append(CHAR_MAP[b])
                j += 1
            elif b < len(CHAR_MAP):
                chars.append(CHAR_MAP[b])
                j += 1
            else:
                j += 1
        strings.append("".join(chars))
    return strings


def dump_select_eur_flow(args):
    """Extract EUR select strings from forest_1st_script.arc → text file."""
    sys.path.insert(0, str(TOOLS_DIR))
    from select_tool import VALID_COUNT

    eur_1st_script = Path(args.eur_1st_script_arc).resolve()
    if not eur_1st_script.exists():
        raise FileNotFoundError(f"EUR forest_1st_script.arc not found: {eur_1st_script}")

    out_txt = Path(args.out).resolve()
    print(f"EUR 1st_script arc : {eur_1st_script}")
    print(f"Output text        : {out_txt}")

    with tempfile.TemporaryDirectory() as tmp:
        strings = _extract_eur_select_strings(eur_1st_script, tmp)

    # Write translation file
    lines = [
        "# Animal Crossing — select string translation file",
        f"# Auto-populated from EUR disc by tools/l10n_flow.py dump-select-eur",
        "#",
        "# Instructions:",
        "#   Translate the line after each [[ENTRY N]] marker.",
        "#   Leave the entry blank if you want the game to show spaces.",
        "#   Max display length is 16 characters (longer strings are clipped).",
        "#   Use the AC charset: standard ASCII plus accented chars",
        "#   (à é è ì ò ù â ê î ô û ä ë ï ö ü ñ ç ß Ä Ö Ü etc.).",
        "#   Do NOT modify the [[ENTRY N]] markers or comment lines.",
        "#   Control codes (<<...>>) are NOT supported in select strings.",
        "",
    ]
    for i in range(VALID_COUNT):
        lines.append(f"[[ENTRY {i}]]")
        if i < len(strings) and strings[i]:
            lines.append(strings[i])
        lines.append("")

    out_txt.parent.mkdir(parents=True, exist_ok=True)
    out_txt.write_text("\n".join(lines), encoding="utf-8")
    non_empty = sum(1 for s in strings[:VALID_COUNT] if s)
    print(f"Dumped {non_empty} non-empty entries (of {VALID_COUNT}) → {out_txt}")


# EUR escape-code → USA escape-code mapping for forest_1st_script bins.
# EUR uses  0x80 0x05 0x04 0x00 <code>  (5-byte sequence).
# USA uses  0x7F <byte>               (2-byte sequence).
# Codes 0x10-0x1B map via +0x12; codes 0x1C-0x25 map via +0x1A.
# Codes 0x10 and 0x11 (article/category helpers) have no USA equivalent → skip.
_EUR_TO_USA_ESCAPE: dict[int, bytes] = {
    code: bytes([0x7F, code + 0x12]) for code in range(0x12, 0x1C)
}
_EUR_TO_USA_ESCAPE.update({
    code: bytes([0x7F, code + 0x1A]) for code in range(0x1C, 0x26)
})

# (BMG-name, USA data-file stem, USA table-slot count, USA padded data size)
_1ST_SCRIPT_BINS: list[tuple[str, str, int, int]] = [
    ("ps",     "ps",     1250, 0x4E20),
    ("psz",    "psz",    750,  0x1388),
    ("super",  "super",  1250, 0x4E20),
    ("superz", "superz", 750,  0x1388),
    ("mail",   "mail",   1500, 0x1FBD0),
    ("maila",  "maila",  750,  0x4E20),
    ("mailb",  "mailb",  750,  0x7530),
    ("mailc",  "mailc",  750,  0x2710),
]


def _eur_raw_to_usa_bytes(raw: bytes) -> bytes:
    """Translate one EUR BMG entry's raw bytes to USA _data.bin format.

    Replaces 5-byte EUR escape sequences (0x80 0x05 0x04 0x00 <code>) with
    their USA 2-byte equivalents (0x7F <byte>).  Stops at a NUL terminator.
    Bytes not part of an escape are passed through unchanged (they are already
    in the AC single-byte charset used by both EUR and USA).
    """
    out = bytearray()
    i = 0
    while i < len(raw):
        b = raw[i]
        if b == 0x80:
            size = raw[i + 1] if i + 1 < len(raw) else 2
            if size == 5 and i + 4 < len(raw) and raw[i + 2] == 0x04 and raw[i + 3] == 0x00:
                code = raw[i + 4]
                if code in _EUR_TO_USA_ESCAPE:
                    out.extend(_EUR_TO_USA_ESCAPE[code])
                # codes 0x10, 0x11 and anything else → silently dropped
            i += max(size, 2)
        elif b == 0x00:
            # AC charset maps 0x00 to '¡' (Spanish opening '!').  A NUL is
            # only a terminator as trailing padding at the end of the slice.
            if not any(x != 0 for x in raw[i:]):
                break
            out.append(b)
            i += 1
        else:
            out.append(b)
            i += 1
    return bytes(out)


def _translate_1st_script_bins(eur_1st_script_arc: Path, pack_arc_root: str,
                                tmp: str) -> None:
    """Replace USA letter/postscript bins with EUR-translated versions.

    Reads each BMG file from EUR forest_1st_script.arc, translates escape
    codes to USA format, and overwrites the corresponding _data.bin /
    _data_table.bin pair inside pack_arc_root.
    """
    from bmg_to_msg import parse_bmg
    import struct

    eur_root = _extract_arc(str(eur_1st_script_arc), os.path.join(tmp, "eur1s_bins"))
    # _extract_arc descends into the single top-level subdirectory, so eur_root
    # is already bin_1st_script/ — the bins live in its "data/" child.
    eur_data_dir = os.path.join(eur_root, "data")
    if not os.path.isdir(eur_data_dir):
        print("  Warning: bin_1st_script/data not found in EUR arc — skipping letter bin translation.")
        return

    for bmg_stem, usa_stem, table_slots, data_pad in _1ST_SCRIPT_BINS:
        bmg_path = os.path.join(eur_data_dir, f"{bmg_stem}.bin")
        if not os.path.exists(bmg_path):
            print(f"  Warning: {bmg_stem}.bin not found in EUR arc — skipping.")
            continue

        data_dst  = _find_file(pack_arc_root, f"{usa_stem}_data.bin")
        table_dst = _find_file(pack_arc_root, f"{usa_stem}_data_table.bin")
        if not data_dst or not table_dst:
            print(f"  Warning: {usa_stem}_data.bin not found in USA arc — skipping.")
            continue

        try:
            offsets, dat1 = parse_bmg(Path(bmg_path).read_bytes())
        except Exception as exc:
            print(f"  Warning: could not parse {bmg_stem}.bin ({exc}) — skipping.")
            continue

        # Build translated data + table
        data_out  = bytearray()
        table_out = bytearray(table_slots * 4)  # zero-filled

        n_translated = 0
        for i in range(min(len(offsets), table_slots)):
            start = offsets[i]
            end   = offsets[i + 1] if i + 1 < len(offsets) else len(dat1)
            raw   = dat1[start:end]
            translated = _eur_raw_to_usa_bytes(raw)
            if translated:
                n_translated += 1
            data_out.extend(translated)
            struct.pack_into(">I", table_out, i * 4, len(data_out))
        # Remaining slots beyond EUR entry count stay zero (empty)

        if len(data_out) < data_pad:
            data_out.extend(b"\x00" * (data_pad - len(data_out)))

        Path(data_dst).write_bytes(bytes(data_out))
        Path(table_dst).write_bytes(bytes(table_out))
        print(f"  Translated {n_translated} entries for {usa_stem}_data.bin")


def _build_forest_1st(usa_1st_arc: Path, lang: str, out_arc: Path,
                      tmp: str, select_txt: str | None,
                      eur_1st_script_arc: Path | None = None) -> None:
    """Pack forest_1st.<lang>.arc with translated (or English) select strings."""
    from select_tool import pack as sel_pack

    # Extract USA forest_1st.arc
    print("\nExtracting USA forest_1st.arc...")
    usa_root = _extract_arc(str(usa_1st_arc), os.path.join(tmp, "usa1"))

    usa_sel_data  = _find_file(usa_root, "select_data.bin")
    usa_sel_table = _find_file(usa_root, "select_data_table.bin")
    if not usa_sel_data or not usa_sel_table:
        raise FileNotFoundError(
            "select_data.bin or select_data_table.bin not found in USA forest_1st.arc"
        )

    pack_arc_root = os.path.join(tmp, "pack1", os.path.basename(usa_root))
    shutil.copytree(usa_root, pack_arc_root)

    # Translate NPC names in string_data.bin
    _translate_npc_names(pack_arc_root, lang)

    # Translate letter/postscript bins from EUR forest_1st_script.arc
    if eur_1st_script_arc and eur_1st_script_arc.exists():
        print("  Translating letter and postscript bins from EUR arc...")
        _translate_1st_script_bins(eur_1st_script_arc, pack_arc_root, tmp)

    # Determine the select text to use: explicit file > auto-extract from EUR > English fallback
    effective_select_txt = select_txt
    if not effective_select_txt and eur_1st_script_arc and eur_1st_script_arc.exists():
        print(f"  Auto-extracting select strings from EUR arc: {eur_1st_script_arc}")
        auto_txt = os.path.join(tmp, f"select_auto_{lang}.txt")
        strings = _extract_eur_select_strings(eur_1st_script_arc, tmp)
        from select_tool import VALID_COUNT
        lines = [
            "# Auto-populated from EUR disc",
            "",
        ]
        for i in range(VALID_COUNT):
            lines.append(f"[[ENTRY {i}]]")
            if i < len(strings) and strings[i]:
                lines.append(strings[i])
            lines.append("")
        Path(auto_txt).write_text("\n".join(lines), encoding="utf-8")
        effective_select_txt = auto_txt

    if effective_select_txt:
        print(f"  Using translated select strings: {effective_select_txt}")
        data_sz  = Path(usa_sel_data).stat().st_size
        table_sz = Path(usa_sel_table).stat().st_size
        data_dst  = _find_file(pack_arc_root, "select_data.bin")
        table_dst = _find_file(pack_arc_root, "select_data_table.bin")
        sel_pack(effective_select_txt, data_dst, table_dst, data_sz, table_sz)
    else:
        print("  No translated select strings provided — using English strings.")

    print(f"\nPacking forest_1st.{lang}.arc...")
    _pack_arc(pack_arc_root, str(out_arc))
    print(f"  → {out_arc}")


# ── from-eur ──────────────────────────────────────────────────────────────────

def from_eur_flow(args):
    """Build translation-ready forest_2nd + forest_1st arcs from a EUR TGC."""
    sys.path.insert(0, str(TOOLS_DIR))

    eur_arc   = Path(args.eur_arc).resolve()
    usa_2nd   = Path(args.usa_arc).resolve()
    usa_1st   = Path(args.usa_1st_arc).resolve() if args.usa_1st_arc else None
    lang      = args.lang
    select_txt = args.select_txt

    if not eur_arc.exists():
        raise FileNotFoundError(f"EUR arc not found: {eur_arc}")
    if not usa_2nd.exists():
        raise FileNotFoundError(f"USA forest_2nd arc not found: {usa_2nd}")
    if usa_1st and not usa_1st.exists():
        raise FileNotFoundError(f"USA forest_1st arc not found: {usa_1st}")

    # Resolve output paths
    if args.out:
        out_2nd = Path(args.out).resolve()
    else:
        base_name, _ = parse_localized_archive_name(usa_2nd)
        out_2nd = ROOT_DIR / "translations" / lang / f"{base_name}.{lang}{usa_2nd.suffix}"

    if usa_1st:
        base_1st, _ = parse_localized_archive_name(usa_1st)
        out_1st = ROOT_DIR / "translations" / lang / f"{base_1st}.{lang}{usa_1st.suffix}"
    else:
        out_1st = None

    print(f"EUR arc      : {eur_arc}")
    print(f"USA 2nd arc  : {usa_2nd}")
    if usa_1st:
        print(f"USA 1st arc  : {usa_1st}")
    if select_txt:
        print(f"Select text  : {select_txt}")
    print(f"Language     : {lang}")
    print(f"Output (2nd) : {out_2nd}")
    if out_1st:
        print(f"Output (1st) : {out_1st}")

    with tempfile.TemporaryDirectory() as tmp:
        _build_forest_2nd(eur_arc, usa_2nd, lang, out_2nd, tmp)

        if usa_1st and out_1st:
            # Auto-detect EUR forest_1st_script.arc alongside the EUR forest_msg.arc
            eur_1st_script = eur_arc.parent / "forest_1st_script.arc"
            _build_forest_1st(usa_1st, lang, out_1st, tmp, select_txt,
                               eur_1st_script if eur_1st_script.exists() else None)

    # Extract translated item/furniture names from EUR forestd.rel.szs
    eur_forestd = eur_arc.parent / "forestd.rel.szs"
    if eur_forestd.exists():
        print("\nExtracting translated item names from EUR forestd.rel.szs...")
        out_trans = ROOT_DIR / "translations" / lang
        n = _extract_item_names(eur_forestd, out_trans, lang)
        print(f"  → translations/{lang}/assets/  ({n} files)")
    else:
        print(f"\n  (forestd.rel.szs not found alongside forest_msg.arc — skipping item names)")

    print(f"\nDone! Drop the files into translations/{lang}/ and set")
    print(f"  language = {lang}")
    print(f"in settings.ini.")


# ── dump-select ───────────────────────────────────────────────────────────────

def dump_select_flow(args):
    """Dump USA player-choice strings from forest_1st.arc to a text file."""
    sys.path.insert(0, str(TOOLS_DIR))
    from select_tool import dump as sel_dump

    usa_1st = Path(args.usa_1st_arc).resolve()
    if not usa_1st.exists():
        raise FileNotFoundError(f"USA forest_1st.arc not found: {usa_1st}")

    out_txt = Path(args.out).resolve()

    print(f"USA 1st arc : {usa_1st}")
    print(f"Output text : {out_txt}")

    with tempfile.TemporaryDirectory() as tmp:
        usa_root = _extract_arc(str(usa_1st), os.path.join(tmp, "usa1"))
        sel_data  = _find_file(usa_root, "select_data.bin")
        sel_table = _find_file(usa_root, "select_data_table.bin")
        if not sel_data or not sel_table:
            raise FileNotFoundError(
                "select_data.bin or select_data_table.bin not found in forest_1st.arc"
            )
        out_txt.parent.mkdir(parents=True, exist_ok=True)
        sel_dump(sel_data, sel_table, str(out_txt))

    print(f"\nTemplate written to {out_txt}")
    print("Translate each line after [[ENTRY N]], then pass the file to from-eur")
    print("  via --select-txt, or rebuild with:")
    print("  python -m tools.select_tool pack <text> <data.bin> <table.bin>")


# ── CLI ───────────────────────────────────────────────────────────────────────

def build_parser():
    parser = argparse.ArgumentParser(
        description="Localization workflow for the Animal Crossing GCN PC port.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # extract
    p = sub.add_parser("extract", help="Unpack archive and dump USA messages for editing")
    p.add_argument("--arc", required=True, help="Source archive (usually orig/GAFE01_00/files/forest_2nd.arc)")
    p.add_argument("--workdir", required=True, help="Working directory for unpacked archive + metadata")
    p.add_argument("--text", help="Output message text file (default: <workdir>/message_dump.txt)")
    p.add_argument("--force", action="store_true", help="Overwrite existing workdir")
    p.set_defaults(func=extract_flow)

    # repack
    p = sub.add_parser("repack", help="Encode edited messages and repack archive")
    p.add_argument("--workdir", required=True, help="Working directory created by extract")
    p.add_argument("--text", help="Edited message text file (default: the one created by extract)")
    p.add_argument("--out", help="Output arc path (default: translations/<lang>/forest_2nd.<lang>.arc)")
    p.add_argument("--language", help="Language tag for default output path (e.g. pt-BR)")
    p.add_argument("--replace-source", action="store_true", help="Backup and replace the original source arc")
    p.set_defaults(func=repack_flow)

    # from-eur
    p = sub.add_parser(
        "from-eur",
        help="Build localized arcs directly from an EUR TGC file (no manual translation)",
        description=(
            "Merges EUR text into the USA message structure and produces\n"
            "forest_2nd.<lang>.arc (NPC dialogue) and optionally\n"
            "forest_1st.<lang>.arc (player-choice strings).\n\n"
            "The USA control codes (NPC expressions, choice branching, item-name\n"
            "substitution) are preserved; only the text runs are replaced.\n\n"
            "EUR TGC files live in orig/GAFP01_00/tgc_<Lang>/files/forest_msg.arc\n"
            "after running:  dtk disc extract orig/GAFP01_00/game.ciso orig/GAFP01_00/\n"
            "                dtk disc extract <tgc_path> orig/GAFP01_00/tgc_<Lang>/\n\n"
            "Player-choice strings (forest_1st) are NOT stored in the EUR TGC;\n"
            "supply a translated text file via --select-txt (produced by\n"
            "  python -m tools.select_tool dump ... select_template.txt\n"
            "then translated by hand).\n"
            "Without --select-txt the English strings are used as a fallback."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--eur-arc", required=True,
                   help="EUR forest_msg.arc (e.g. orig/GAFP01_00/tgc_Frn/files/forest_msg.arc)")
    p.add_argument("--usa-arc", required=True,
                   help="USA forest_2nd.arc (orig/GAFE01_00/files/forest_2nd.arc)")
    p.add_argument("--usa-1st-arc",
                   help="USA forest_1st.arc (orig/GAFE01_00/files/forest_1st.arc); "
                        "required to also generate a translated forest_1st arc")
    p.add_argument("--select-txt",
                   help="Translated select-string text file for --usa-1st-arc "
                        "(produced by: python -m tools.select_tool dump ...); "
                        "omit to keep English choice strings")
    p.add_argument("--lang", required=True,
                   help="Language tag used for the output path (e.g. fr-FR, de-DE, it-IT, es-ES, en-EU)")
    p.add_argument("--out", help="Override output arc path for forest_2nd")
    p.set_defaults(func=from_eur_flow)

    # dump-select
    p = sub.add_parser(
        "dump-select",
        help="Dump USA player-choice strings to a text file for translation",
    )
    p.add_argument("--usa-1st-arc", required=True,
                   help="USA forest_1st.arc (orig/GAFE01_00/files/forest_1st.arc)")
    p.add_argument("--out", required=True,
                   help="Output text file (e.g. text/select_Itl.txt)")
    p.set_defaults(func=dump_select_flow)

    # dump-select-eur
    p = sub.add_parser(
        "dump-select-eur",
        help="Auto-populate a select-string text file from an EUR forest_1st_script.arc",
        description=(
            "Extracts the translated player-choice strings from the EUR disc's\n"
            "forest_1st_script.arc (select.bin in BMG format) and writes them\n"
            "into a ready-to-use translation text file — no manual translation needed.\n\n"
            "Example:\n"
            "  python -m tools.l10n_flow dump-select-eur \\\n"
            "      --eur-1st-script-arc orig/GAFP01_00/tgc_Itl/files/forest_1st_script.arc \\\n"
            "      --out text/select_Itl.txt"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--eur-1st-script-arc", required=True,
                   help="EUR forest_1st_script.arc "
                        "(e.g. orig/GAFP01_00/tgc_Itl/files/forest_1st_script.arc)")
    p.add_argument("--out", required=True,
                   help="Output text file (e.g. text/select_Itl.txt)")
    p.set_defaults(func=dump_select_eur_flow)

    return parser


def main():
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
