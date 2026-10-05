#!/usr/bin/env python3
"""Map ACHD Localisation/GER textures (hashes of PAL German data) to USA texture hashes.

German texture data is located in the German foresta.rel by XXH64, resolved to a
symbol via the German foresta.map, then the same symbol is looked up in the USA
REL/map and its data rehashed. Output: copies of the GER .dds files renamed to the
USA hash (TLUT hash kept: palettes are identical between versions).

Used to create app/src/main/assets/l10n/de-DE_textures.json (run once with
GER_REL=forestd and once with GER_REL=foresto, collect the "->" lines).

Usage: map_ger_textures.py <ACHD GAF/Localisation/GER dir> <out dir> <ger_tgc_dir> <usa_dir>
  ger_tgc_dir: files of tgc/forest_Gmn_Final_PAL50.tgc from the EUR disc (forestd.rel.szs, .map, ...)
  usa_dir:     foresta.rel (Yaz0-decompressed) and foresta.map from the USA disc
Requires: pip install xxhash"""
import os
import re
import shutil
import struct
import sys

import xxhash

GER_DIR, OUT_DIR, GER_TGC_DIR, USA_DIR = sys.argv[1:5]
GER_REL = os.environ.get("GER_REL", "forestd")
BPP = {0: 4, 1: 8, 2: 8, 3: 16, 4: 16, 5: 16, 6: 32, 8: 4, 9: 8, 10: 16, 14: 4}


def yaz0(src):
    assert src[:4] == b"Yaz0"
    size = struct.unpack_from(">I", src, 4)[0]
    dst = bytearray(size)
    s, d = 16, 0
    while d < size:
        code = src[s]
        s += 1
        for bit in range(8):
            if d >= size:
                break
            if code & (0x80 >> bit):
                dst[d] = src[s]
                d += 1
                s += 1
            else:
                b1, b2 = src[s], src[s + 1]
                s += 2
                dist = ((b1 & 0xF) << 8 | b2) + 1
                n = b1 >> 4
                if n == 0:
                    n = src[s] + 0x12
                    s += 1
                else:
                    n += 2
                for _ in range(n):
                    dst[d] = dst[d - dist]
                    d += 1
    return bytes(dst)


def rel_sections(rel):
    num, info = struct.unpack_from(">II", rel, 0x0C)
    secs = []
    for i in range(num):
        off, size = struct.unpack_from(">II", rel, info + i * 8)
        secs.append((off & ~1, size))
    return secs


def parse_map(path):
    """symbol -> (section_name, addr, size); also per-section sorted list."""
    syms, by_sec = {}, {}
    sec = None
    for line in open(path, encoding="latin1"):
        m = re.match(r"^(\.\w+) section layout", line)
        if m:
            sec = m.group(1)
            continue
        m = re.match(r"^\s+([0-9a-f]{8}) ([0-9a-f]{6}) [0-9a-f]{8}\s+\d+ (\S+)\s+(\S+)", line)
        if m and sec:
            addr, size, name, obj = int(m.group(1), 16), int(m.group(2), 16), m.group(3), m.group(4)
            if name.startswith("."):
                continue
            key = (name, obj)
            syms[key] = (sec, addr, size)
            by_sec.setdefault(sec, []).append((addr, size, key))
    for v in by_sec.values():
        v.sort()
    return syms, by_sec


def section_offsets(rel, mp_by_sec):
    """Match map section names to REL section file offsets by size."""
    secs = rel_sections(rel)
    out = {}
    for name, entries in mp_by_sec.items():
        end = max(a + s for a, s, _ in entries)
        cands = [(sz, off) for off, sz in secs if off and sz >= end and sz - end < 0x100]
        if cands:
            out[name] = min(cands)[1]
    return out


def main():
    ger_rel = yaz0(open(f"{GER_TGC_DIR}/{GER_REL}.rel.szs", "rb").read())
    usa_rel = open(f"{USA_DIR}/foresta.rel", "rb").read()
    ger_syms, ger_by_sec = parse_map(f"{GER_TGC_DIR}/{GER_REL}.map")
    usa_syms, usa_by_sec = parse_map(f"{USA_DIR}/foresta.map")
    ger_off = section_offsets(ger_rel, ger_by_sec)
    usa_off = section_offsets(usa_rel, usa_by_sec)
    print("sections ger", {k: hex(v) for k, v in ger_off.items()})
    print("sections usa", {k: hex(v) for k, v in usa_off.items()})

    files = sorted(f for f in os.listdir(GER_DIR) if f.endswith(".dds"))
    want = {}
    for f in files:
        m = re.match(r"tex1_(\d+)x(\d+)_([0-9a-f]{16})(?:_([0-9a-f]{16}))?_(\d+)\.dds", f)
        w, h, hsh, tlut, fmt = int(m[1]), int(m[2]), int(m[3], 16), m[4], int(m[5])
        size = w * h * BPP[fmt] // 8
        want.setdefault(size, {})[hsh] = (f, w, h, tlut, fmt)

    found = {}
    for size, table in want.items():
        for off in range(0, len(ger_rel) - size, 32):
            hv = xxhash.xxh64_intdigest(ger_rel[off:off + size])
            if hv in table:
                found[table[hv][0]] = (off, size, table[hv])
    print(f"located {len(found)}/{len(files)} GER textures in German foresta.rel")

    os.makedirs(OUT_DIR, exist_ok=True)
    mapped = 0
    for f, (off, size, (_, w, h, tlut, fmt)) in sorted(found.items()):
        hit = None
        for sec, base in ger_off.items():
            rel_addr = off - base
            for addr, ssz, key in ger_by_sec.get(sec, []):
                if addr <= rel_addr < addr + ssz:
                    hit = (sec, key, rel_addr - addr)
                    break
            if hit:
                break
        ukey = None
        if hit:
            if hit[1] in usa_syms:
                ukey = hit[1]
            else:  # same symbol under another object file, or German "_ge" variant name
                for cand in (hit[1][0], hit[1][0].replace("_ge_", "_").replace("_ge", "")):
                    ukey = next((k for k in usa_syms if k[0] == cand), None)
                    if ukey:
                        break
        if not ukey or usa_syms[ukey][0] not in usa_off:
            print("  no symbol/USA match:", f, hit and hit[1])
            continue
        sec, key, delta = hit
        usec, uaddr, usz = usa_syms[ukey]
        uoff = usa_off[usec] + uaddr + delta
        uhash = xxhash.xxh64_intdigest(usa_rel[uoff:uoff + size])
        name = f"tex1_{w}x{h}_{uhash:016x}" + (f"_{tlut}" if tlut else "") + f"_{fmt}.dds"
        shutil.copy(os.path.join(GER_DIR, f), os.path.join(OUT_DIR, name))
        mapped += 1
        print(f"  {key[0]:<32} {f} -> {name}")
    print(f"mapped {mapped}/{len(files)}")


main()
