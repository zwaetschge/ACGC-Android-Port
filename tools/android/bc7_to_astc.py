#!/usr/bin/env python3
"""Desktop alternative to the in-app converter: convert the ACHD (Dolphin, DDS BC7) texture pack to DDS ASTC 4x4 for Android GPUs
without BPTC. Output keeps Dolphin filenames; DX10 header gets DXGI format 134
(ASTC_4X4_UNORM), which pc_texture_pack.c maps to GL_COMPRESSED_RGBA_ASTC_4x4_KHR."""
import os
import struct
import sys
from multiprocessing import Pool

import numpy as np
from PIL import Image
import texture2ddecoder
from astc_encoder import ASTCConfig, ASTCContext, ASTCImage, ASTCProfile, ASTCSwizzle, ASTCType

SRC = sys.argv[1]
DST = sys.argv[2]
QUALITY = float(sys.argv[3]) if len(sys.argv) > 3 else 60.0
DXGI_BC7 = 98
DXGI_ASTC_4X4 = 134

_ctx = None


def skip(rel):
    parts = rel.split(os.sep)
    if "Localisation" in parts:
        return True  # GER/SPA textures belong to the PAL release
    if "Font" in parts and parts[-2] != "GAFE":
        return True  # only the USA (GAFE) font matches this build
    return False


def convert(job):
    global _ctx
    src, dst = job
    if os.path.exists(dst):
        return "skip"
    if _ctx is None:
        _ctx = ASTCContext(ASTCConfig(ASTCProfile.LDR, 4, 4, 1, QUALITY))
    with open(src, "rb") as f:
        data = f.read()
    if data[:4] != b"DDS " or data[84:88] != b"DX10":
        return "notdx10"
    h, w = struct.unpack_from("<II", data, 12)
    if struct.unpack_from("<I", data, 128)[0] != DXGI_BC7:
        return "notbc7"
    size = ((w + 3) // 4) * ((h + 3) // 4) * 16
    bgra = texture2ddecoder.decode_bc7(data[148:148 + size], w, h)
    rgba = np.frombuffer(bgra, np.uint8).reshape(-1, 4)[:, [2, 1, 0, 3]].tobytes()
    # Full mip chain: HD textures are drawn far smaller than their size; without
    # mips the GPU aliases and wastes bandwidth.
    swz = ASTCSwizzle.from_str("RGBA")
    img = Image.frombytes("RGBA", (w, h), rgba)
    levels = []
    lw, lh = w, h
    while True:
        levels.append(_ctx.compress(ASTCImage(ASTCType.U8, lw, lh, 1, img.tobytes()), swz))
        if lw == 1 and lh == 1:
            break
        lw, lh = max(1, lw // 2), max(1, lh // 2)
        img = img.resize((lw, lh), Image.BOX)
    comp = b"".join(levels)
    hdr = bytearray(data[:148])
    struct.pack_into("<I", hdr, 128, DXGI_ASTC_4X4)
    struct.pack_into("<I", hdr, 8, struct.unpack_from("<I", hdr, 8)[0] | 0x20000)  # DDSD_MIPMAPCOUNT
    struct.pack_into("<I", hdr, 28, len(levels))
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    tmp = dst + ".tmp"
    with open(tmp, "wb") as f:
        f.write(hdr)
        f.write(comp)
    os.replace(tmp, dst)
    return "ok"


def main():
    jobs = []
    for root, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".dds"):
                continue
            src = os.path.join(root, name)
            rel = os.path.relpath(src, SRC)
            if skip(rel):
                continue
            jobs.append((src, os.path.join(DST, rel)))
    print(f"{len(jobs)} textures", flush=True)
    stats = {}
    with Pool(int(os.environ.get("JOBS", "48"))) as pool:
        for i, res in enumerate(pool.imap_unordered(convert, jobs, chunksize=8), 1):
            stats[res] = stats.get(res, 0) + 1
            if i % 1000 == 0:
                print(i, stats, flush=True)
    print("done", stats, flush=True)


if __name__ == "__main__":
    main()
