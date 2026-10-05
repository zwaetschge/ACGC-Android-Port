"""bmg_to_msg.py - Merge EUR BMG text into USA message_data.bin format.

Strategy
--------
USA and EUR entries are aligned 1-to-1 (both have 16 273 non-zero message
slots in the same order).  Within each entry the TEXT RUNS (plain characters
between control codes) carry the language; the CONTROL CODES carry the game
mechanics (NPC expressions, pauses, item-name placeholders, choice branching).

This tool rebuilds message_data.bin by keeping every USA control code intact
and replacing each text run with the corresponding EUR text run.  When run
counts do not match (rare structural differences between languages), the
whole EUR text is appended as one block before the USA closing control codes.

Usage
-----
    python -m tools.bmg_to_msg \\
        --eur  orig/GAFP01_00/tgc_Frn/files/forest_msg.arc \\
        --usa  orig/GAFE01_00/files/forest_2nd.arc \\
        --lang fr-FR \\
        --out  translations/fr-FR/forest_2nd.fr-FR.arc

    # or work on already-extracted files:
    python -m tools.bmg_to_msg \\
        --eur-bin  /tmp/eur_msg/Frn/bin_msg/data/msg.bin \\
        --usa-data /tmp/usa_2nd/bin2/data/message_data.bin \\
        --usa-table /tmp/usa_2nd/bin2/data/message_data_table.bin \\
        --out-data  /tmp/out/message_data.bin \\
        --out-table /tmp/out/message_data_table.bin
"""

import argparse
import struct
import sys
import os
import tempfile
import shutil
from pathlib import Path

# Reuse charset from msg_tool; BMG uses the same byte values for text chars
EUR_ESCAPE = 0x80
USA_ESCAPE = 0x7F


# ── BMG parsing ───────────────────────────────────────────────────────────────

def parse_bmg(data: bytes):
    """Return (offsets_list, dat1_payload) from raw BMG bytes."""
    if data[:8] != b"MESGbmg1":
        raise ValueError("Not a BMG file")
    num_blocks = struct.unpack_from(">I", data, 12)[0]
    offset = 32
    inf1_offsets = None
    dat1_payload = None
    for _ in range(num_blocks):
        magic = data[offset:offset + 4]
        block_size = struct.unpack_from(">I", data, offset + 4)[0]
        if magic == b"INF1":
            n = struct.unpack_from(">H", data, offset + 8)[0]
            esz = struct.unpack_from(">H", data, offset + 10)[0]
            base = offset + 16
            inf1_offsets = [struct.unpack_from(">I", data, base + i * esz)[0] for i in range(n)]
        elif magic == b"DAT1":
            dat1_payload = data[offset + 8:offset + block_size]
        offset += block_size
    if inf1_offsets is None or dat1_payload is None:
        raise ValueError("BMG missing INF1 or DAT1")
    return inf1_offsets, dat1_payload


def eur_entry_bytes(dat1: bytes, offsets: list, idx: int) -> bytes:
    start = offsets[idx]
    end = offsets[idx + 1] if idx + 1 < len(offsets) else len(dat1)
    return dat1[start:end]


# ── USA parsing ───────────────────────────────────────────────────────────────

def parse_usa_table(table_bytes: bytes):
    n = len(table_bytes) // 4
    return [struct.unpack_from(">I", table_bytes, i * 4)[0] for i in range(n)]


def usa_entry_bytes(data: bytes, table: list, idx: int) -> bytes:
    end = table[idx]
    if end == 0:
        return b""
    start = table[idx - 1] if idx > 0 else 0
    # Walk back to find the real previous non-zero end
    prev = 0
    for j in range(idx - 1, -1, -1):
        if table[j] != 0:
            prev = table[j]
            break
    return data[prev:end]


def iter_usa_entries(data: bytes, table: list):
    """Yield (index, entry_bytes) for every non-empty USA entry."""
    last = 0
    for i, end in enumerate(table):
        if end != 0:
            yield i, data[last:end]
            last = end


# ── EUR preprocessing ─────────────────────────────────────────────────────────

# EUR gender-switch control code (variable length):
#   80 <SIZE> 13 00 14 <masc_bytes> FE <fem_bytes>
# SIZE = total bytes including the 80 and SIZE bytes themselves.
# Minimum: 80 08 13 00 14 X FE Y = 8 bytes (single-char masc/fem).
# Larger codes carry multi-char strings, e.g. "l'amico" / "l'amica".
#
# Converted to USA mFont_CONT_CODE_GENDER_CHAR (index 0x6B = 107):
#   <common_prefix> 7F 6B <masc_final_byte> <fem_final_byte>
# When masc and fem share a common prefix (common in Romance languages),
# that prefix is emitted as plain text and only the differing final byte
# is placed in the GENDER_CHAR code.  If the two strings differ by more
# than one byte, the masculine form is emitted as plain text (fallback).
_EUR_GENDER_ESCAPE = 0x80
_EUR_GENDER_TYPE   = bytes([0x13, 0x00, 0x14])
_EUR_GENDER_SEP    = 0xFE
# USA GENDER_CHAR opcode (mFont_CONT_CODE_GENDER_CHAR = index 107 = 0x6B)
_USA_GENDER_CHAR_OP = bytes([0x7F, 0x6B])


def preprocess_eur_raw(raw: bytes) -> bytes:
    """Convert EUR gender-switch codes to USA GENDER_CHAR control codes.

    Handles both single-byte forms (8-byte total) and multi-byte forms such as
    "l'amico" / "l'amica" (14-byte total).  When masc and fem share a common
    prefix that differ only in a single final byte, emits:
        <common_prefix_bytes>  7F 6B <masc_tail> <fem_tail>
    Otherwise falls back to emitting the masculine string as plain text.
    """
    if _EUR_GENDER_ESCAPE not in raw:
        return raw

    out = bytearray()
    i = 0
    while i < len(raw):
        if (raw[i] == _EUR_GENDER_ESCAPE
                and i + 5 < len(raw)):
            size = raw[i + 1]
            if (size >= 8
                    and i + size <= len(raw)
                    and raw[i + 2:i + 5] == _EUR_GENDER_TYPE):
                payload = raw[i + 5:i + size]   # bytes after "13 00 14"
                sep_pos = payload.find(_EUR_GENDER_SEP)
                if sep_pos >= 0:
                    masc = payload[:sep_pos]
                    fem  = payload[sep_pos + 1:]
                    # Find common prefix length
                    pfx = 0
                    while pfx < len(masc) and pfx < len(fem) and masc[pfx] == fem[pfx]:
                        pfx += 1
                    masc_tail = masc[pfx:]
                    fem_tail  = fem[pfx:]
                    if len(masc_tail) == 1 and len(fem_tail) == 1:
                        # Common case: texts share a prefix and differ only in
                        # the final byte (e.g. "l'amico"/"l'amica" → common
                        # prefix "l'amic", tails 'o'/'a').
                        out += masc[:pfx]
                        out += _USA_GENDER_CHAR_OP
                        out.append(masc_tail[0])
                        out.append(fem_tail[0])
                    else:
                        # Multi-byte tail difference: fall back to masculine text.
                        out += masc
                    i += size
                    continue
        out.append(raw[i])
        i += 1
    return bytes(out)


# ── Segment splitting ─────────────────────────────────────────────────────────

def split_into_segments(raw: bytes, escape: int):
    """Split raw entry bytes into alternating cmd/text tuples.

    Returns list of ('cmd', bytes) or ('text', bytes) items.
    """
    segments = []
    i = 0
    text_start = 0

    while i < len(raw):
        b = raw[i]
        if b == escape:
            # Flush any pending text
            if i > text_start:
                segments.append(("text", raw[text_start:i]))
            # Determine control code size
            if i + 1 >= len(raw):
                segments.append(("cmd", raw[i:i + 1]))
                i += 1
                text_start = i
                continue
            cmd_type = raw[i + 1]
            if escape == EUR_ESCAPE:
                size = max(int(cmd_type), 2)
            else:
                # USA: look up fixed sizes
                from msg_tool import CONT_SIZES
                size = CONT_SIZES[cmd_type] if cmd_type < len(CONT_SIZES) else 2
            seg_end = min(i + size, len(raw))
            segments.append(("cmd", raw[i:seg_end]))
            i = seg_end
            text_start = i
        else:
            i += 1

    # Flush trailing text
    if text_start < len(raw):
        segments.append(("text", raw[text_start:]))

    return segments


# ── Merge ─────────────────────────────────────────────────────────────────────

def merge_entry(usa_raw: bytes, eur_raw: bytes) -> bytes:
    """Return a new entry using USA control codes and EUR text runs.

    If the number of text segments differs, appends all EUR text to the first
    USA text slot so the entry at least displays the right language.
    """
    usa_segs = split_into_segments(usa_raw, USA_ESCAPE)
    eur_segs = split_into_segments(eur_raw, EUR_ESCAPE)

    usa_texts = [(i, s) for i, s in enumerate(usa_segs) if s[0] == "text"]
    eur_texts = [s[1] for s in eur_segs if s[0] == "text"]

    # Merge EUR text runs into USA structure
    if len(usa_texts) == len(eur_texts) and len(usa_texts) > 0:
        # Happy path: same number of text slots
        result = list(usa_segs)
        for (slot_idx, _), eur_text in zip(usa_texts, eur_texts):
            result[slot_idx] = ("text", eur_text)
        return b"".join(seg[1] for seg in result)

    if len(usa_texts) == 0:
        # USA entry is all control codes (e.g. empty/redirect)
        return usa_raw

    # Fallback: put all EUR text into the first USA text slot, drop the rest
    all_eur_text = b"".join(eur_texts)
    result = []
    first_replaced = False
    for kind, payload in usa_segs:
        if kind == "text" and not first_replaced:
            result.append(all_eur_text)
            first_replaced = True
        elif kind == "text":
            pass  # drop extra USA text slots
        else:
            result.append(payload)
    return b"".join(result)


# ── High-level conversion ─────────────────────────────────────────────────────

def convert_message_data(
    eur_bmg_data: bytes,
    usa_msg_data: bytes,
    usa_msg_table: bytes,
) -> tuple:
    """Return (new_msg_data, new_msg_table) with EUR text and USA control codes."""
    eur_offsets, eur_dat1 = parse_bmg(eur_bmg_data)
    usa_table = parse_usa_table(usa_msg_table)

    new_data = bytearray()
    new_table = bytearray(len(usa_msg_table))

    last_usa = 0
    eur_idx = 0  # EUR BMG index tracks alongside USA non-zero entries

    stats = {"merged": 0, "fallback": 0, "empty": 0, "eur_only": 0}

    for i, end in enumerate(usa_table):
        if end == 0:
            # Zero slot: preserve (no message)
            struct.pack_into(">I", new_table, i * 4, 0)
            continue

        usa_raw = usa_msg_data[last_usa:end]
        last_usa = end

        if eur_idx < len(eur_offsets):
            eur_raw = eur_entry_bytes(eur_dat1, eur_offsets, eur_idx)
            eur_idx += 1
        else:
            eur_raw = b""

        if not usa_raw:
            stats["empty"] += 1
            struct.pack_into(">I", new_table, i * 4, len(new_data))
            continue

        if not eur_raw:
            # No EUR counterpart — keep USA as-is
            stats["eur_only"] += 1
            new_data.extend(usa_raw)
        else:
            eur_raw_proc = preprocess_eur_raw(eur_raw)
            usa_segs = split_into_segments(usa_raw, USA_ESCAPE)
            eur_segs = split_into_segments(eur_raw_proc, EUR_ESCAPE)
            n_usa_text = sum(1 for k, _ in usa_segs if k == "text")
            n_eur_text = sum(1 for k, _ in eur_segs if k == "text")

            merged = merge_entry(usa_raw, eur_raw_proc)
            new_data.extend(merged)
            if n_usa_text == n_eur_text:
                stats["merged"] += 1
            else:
                stats["fallback"] += 1

        struct.pack_into(">I", new_table, i * 4, len(new_data))

    # Pad to original sizes
    orig_data_size = len(usa_msg_data)
    orig_table_size = len(usa_msg_table)

    if len(new_data) < orig_data_size:
        new_data.extend(b"\x00" * (orig_data_size - len(new_data)))
    if len(new_table) < orig_table_size:
        new_table.extend(b"\x00" * (orig_table_size - len(new_table)))

    print(f"  Clean merges : {stats['merged']}")
    print(f"  Fallback merges: {stats['fallback']}")
    print(f"  USA-only (no EUR): {stats['eur_only']}")

    return bytes(new_data), bytes(new_table)


# ── Arc helpers ───────────────────────────────────────────────────────────────

def extract_arc(arc_path: str, out_dir: str):
    """Extract a JKR archive using pyjkernel."""
    sys.path.insert(0, str(Path(__file__).parent))
    import pyjkernel

    archive = pyjkernel.from_archive_file(arc_path, True)
    orig = os.getcwd()
    os.chdir(out_dir)
    root = archive.root_name
    if not os.path.exists(root):
        os.mkdir(root)

    def _dump(a, d):
        for f in a.list_files(d):
            with open(os.path.join(d, f.name), "wb") as fh:
                fh.write(a.get_file(d + "/" + f.name).data)
        for sub in a.list_folders(d):
            p = d + "/" + sub
            os.makedirs(p, exist_ok=True)
            _dump(a, p)

    _dump(archive, root)
    os.chdir(orig)
    return os.path.join(out_dir, root)


def pack_arc(src_dir: str, out_arc: str):
    """Pack a directory back into a JKR archive."""
    sys.path.insert(0, str(Path(__file__).parent))
    from arc_tool import pack_archive

    pack_archive(src_dir, out_arc)


def find_in_dir(base: str, filename: str):
    for root, _, files in os.walk(base):
        for f in files:
            if f == filename:
                return os.path.join(root, f)
    return None


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Merge EUR BMG text into USA message_data.bin format for the AC PC port."
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--eur-bin", metavar="MSG_BIN", help="Path to EUR msg.bin (already extracted)")
    mode.add_argument("--eur-arc", metavar="ARC", help="Path to EUR forest_msg.arc (will be extracted)")

    parser.add_argument("--usa-data", metavar="BIN", help="Path to USA message_data.bin (already extracted)")
    parser.add_argument("--usa-table", metavar="BIN", help="Path to USA message_data_table.bin")
    parser.add_argument("--usa-arc", metavar="ARC", help="Path to USA forest_2nd.arc (alternative to --usa-data/--usa-table)")

    parser.add_argument("--out-data", metavar="BIN", help="Output message_data.bin path")
    parser.add_argument("--out-table", metavar="BIN", help="Output message_data_table.bin path")
    parser.add_argument("--out-arc", metavar="ARC", help="Output arc path (e.g. translations/fr-FR/forest_2nd.fr-FR.arc)")

    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        # ── Resolve EUR BMG ──────────────────────────────────────────────────
        if args.eur_bin:
            eur_bin_path = args.eur_bin
        else:
            print(f"Extracting EUR arc: {args.eur_arc}")
            eur_root = extract_arc(args.eur_arc, os.path.join(tmp, "eur"))
            eur_bin_path = find_in_dir(eur_root, "msg.bin")
            if not eur_bin_path:
                parser.error("msg.bin not found in EUR arc")

        # ── Resolve USA data/table ───────────────────────────────────────────
        if args.usa_data and args.usa_table:
            usa_data_path = args.usa_data
            usa_table_path = args.usa_table
        elif args.usa_arc:
            print(f"Extracting USA arc: {args.usa_arc}")
            usa_root = extract_arc(args.usa_arc, os.path.join(tmp, "usa"))
            usa_data_path = find_in_dir(usa_root, "message_data.bin")
            usa_table_path = find_in_dir(usa_root, "message_data_table.bin")
            if not usa_data_path or not usa_table_path:
                parser.error("message_data.bin or message_data_table.bin not found in USA arc")
        else:
            parser.error("Provide either --usa-arc or both --usa-data and --usa-table")

        # ── Convert ──────────────────────────────────────────────────────────
        print(f"Reading EUR BMG : {eur_bin_path}")
        with open(eur_bin_path, "rb") as f:
            eur_bmg = f.read()

        print(f"Reading USA data : {usa_data_path}")
        with open(usa_data_path, "rb") as f:
            usa_data = f.read()
        with open(usa_table_path, "rb") as f:
            usa_table = f.read()

        print("Merging...")
        new_data, new_table = convert_message_data(eur_bmg, usa_data, usa_table)

        # ── Write output ─────────────────────────────────────────────────────
        if args.out_arc:
            # Repack into forest_2nd arc
            # Unpack USA arc to get full structure, replace message_data files
            if args.usa_arc:
                usa_root_for_pack = os.path.join(tmp, "usa_pack")
                shutil.copytree(os.path.join(tmp, "usa"), usa_root_for_pack)
            else:
                print("--out-arc requires --usa-arc to get the full archive structure")
                sys.exit(1)

            # Overwrite the message_data files in the unpacked tree
            data_dst = find_in_dir(usa_root_for_pack, "message_data.bin")
            table_dst = find_in_dir(usa_root_for_pack, "message_data_table.bin")
            with open(data_dst, "wb") as f:
                f.write(new_data)
            with open(table_dst, "wb") as f:
                f.write(new_table)

            os.makedirs(os.path.dirname(os.path.abspath(args.out_arc)), exist_ok=True)
            # Find the root dir inside usa_root_for_pack
            root_dirs = [d for d in os.listdir(usa_root_for_pack)
                         if os.path.isdir(os.path.join(usa_root_for_pack, d))]
            if not root_dirs:
                print("Could not find arc root dir to repack")
                sys.exit(1)
            pack_arc(os.path.join(usa_root_for_pack, root_dirs[0]), args.out_arc)
            print(f"Written arc: {args.out_arc}")
        else:
            if not args.out_data or not args.out_table:
                parser.error("Provide --out-arc or both --out-data and --out-table")
            with open(args.out_data, "wb") as f:
                f.write(new_data)
            with open(args.out_table, "wb") as f:
                f.write(new_table)
            print(f"Written data : {args.out_data}")
            print(f"Written table: {args.out_table}")


if __name__ == "__main__":
    main()
