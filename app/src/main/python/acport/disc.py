"""GameCube disc access for ISO/GCM, CISO and NKit (v01) images.

Only what the port needs: the FST, reading files, and writing a compact ISO
(header region + FST + files) that the native disc reader accepts.
"""
import os
import struct

GAP_BLOCK = 0x100

# known file signatures, used to pick the right NKit layout interpretation
MAGIC = {
    ".arc": (b"RARC",),
    ".szs": (b"Yaz0",),
    ".tgc": (b"\xae\x0f\x38\xa2",),
    ".bnr": (b"BNR1", b"BNR2"),
}


def u32be(buf, off):
    return struct.unpack_from(">I", buf, off)[0]


def parse_fst(fst):
    """[(path, disc_offset, length, entry_index)] for every file entry."""
    num = u32be(fst, 8)
    str_off = num * 12
    stack = [(num, "")]
    files = []
    for i in range(1, num):
        flags = fst[i * 12]
        name_off = (fst[i * 12 + 1] << 16) | (fst[i * 12 + 2] << 8) | fst[i * 12 + 3]
        off = u32be(fst, i * 12 + 4)
        length = u32be(fst, i * 12 + 8)
        end = str_off + name_off
        name = fst[end:fst.index(b"\0", end)].decode("ascii", "replace")
        while stack and i >= stack[-1][0]:
            stack.pop()
        parent = "/".join(p for _, p in stack if p)
        if flags & 1:
            stack.append((length, name))
        else:
            files.append((f"{parent}/{name}" if parent else name, off, length, i))
    return files


class Disc:
    def __init__(self, path):
        self.path = path
        self.f = open(path, "rb")
        self.size = os.path.getsize(path)
        head = self._raw(0, 0x440)
        self.kind = "iso"
        if head[:4] == b"CISO":
            self.kind = "ciso"
            self.block = struct.unpack_from("<I", head, 4)[0]
            mp = self._raw(8, 0x8000 - 8)
            self.blocks = {}
            n = 0
            for i, present in enumerate(mp):
                if present:
                    self.blocks[i] = 0x8000 + n * self.block
                    n += 1
            head = self.read(0, 0x440)
        elif head[0x200:0x208] == b"NKIT v01":
            self.kind = "nkit"
        self.header = head
        self.game_id = head[:6].decode("ascii", "replace")
        if u32be(head, 0x1C) != 0xC2339F3D:
            raise ValueError("not a GameCube disc image")
        self.fst_off = u32be(head, 0x424)
        self.fst_size = u32be(head, 0x428)
        self.fst = self.read(self.fst_off, self.fst_size)
        self.files = parse_fst(self.fst)
        self.by_path = {p.lower(): (p, o, n, i) for p, o, n, i in self.files}
        self._nkit_pos = self._nkit_layout() if self.kind == "nkit" else None

    def close(self):
        self.f.close()

    def _raw(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def read(self, off, n):
        """Read n bytes at a disc offset (ISO/CISO; NKit only below the FST end)."""
        if self.kind != "ciso":
            return self._raw(off, n)
        out = bytearray()
        while n > 0:
            blk, inner = divmod(off, self.block)
            take = min(n, self.block - inner)
            pos = self.blocks.get(blk)
            out += self._raw(pos + inner, take) if pos is not None else b"\0" * take
            off += take
            n -= take
        return bytes(out)

    # -- NKit: files are stored without junk; two layouts are seen in the wild --
    def _nkit_walk(self):
        """Positions from the NKit gap descriptors (files packed after the FST)."""
        files = sorted(self.files, key=lambda e: (e[1], e[2]))
        pos = {}
        nk = self.fst_off + self.fst_size
        nk += (4 - nk % 4) % 4

        def consume_gap(nk):
            size = u32be(self._raw(nk, 4), 0)
            nk += 4
            gt = size & 3
            size &= 0xFFFFFFFC
            if gt == 3:
                return nk + 4
            if gt == 2:
                remaining = size
                last = None
                while remaining > 0:
                    blk = u32be(self._raw(nk, 4), 0)
                    nk += 4
                    bt, cnt = blk >> 30, blk & 0x3FFFFFFF
                    if bt != 3:
                        last = bt
                    nbytes = min(cnt * GAP_BLOCK, remaining)
                    if last == 1:
                        nk += nbytes
                    remaining -= nbytes
            return nk

        def align4(x):
            return x + (4 - x % 4) % 4

        prev_end = align4(self.fst_off + self.fst_size)
        for path, off, length, _ in files:
            if off - prev_end > 0:
                nk = consume_gap(nk)
            pos[path] = nk
            nk += align4(length) if length else 0
            prev_end = align4(off + length)
        return pos

    def _valid_at(self, path, pos, length):
        if pos is None or pos + length > self.size:
            return False
        ext = os.path.splitext(path)[1].lower()
        magics = MAGIC.get(ext)
        if not magics:
            return None  # unknown type: no verdict
        head = self._raw(pos, 4)
        return any(head.startswith(m) for m in magics)

    def _nkit_layout(self):
        try:
            walk = self._nkit_walk()
        except Exception:
            walk = {}
        inplace = {p: o for p, o, n, _ in self.files}
        score = {"walk": 0, "inplace": 0}
        for p, o, n, _ in self.files:
            for name, table in (("walk", walk), ("inplace", inplace)):
                if self._valid_at(p, table.get(p), n):
                    score[name] += 1
        return inplace if score["inplace"] > score["walk"] else walk

    def file_bytes(self, path):
        p, off, length, _ = self.by_path[path.lower().lstrip("/")]
        if self.kind == "nkit":
            return self._raw(self._nkit_pos[p], length)
        return self.read(off, length)

    def has(self, path):
        return path.lower().lstrip("/") in self.by_path

    def write_compact_iso(self, out_path):
        """Header region + FST + all files packed after it (what pc_disc needs)."""
        header = bytearray(self.read(0, self.fst_off) if self.kind != "nkit" else self._raw(0, self.fst_off))
        header[0x200:0x220] = b"\0" * 0x20  # drop NKit metadata
        fst = bytearray(self.fst)
        tmp = out_path + ".tmp"
        with open(tmp, "wb") as out:
            out.write(header)
            out.write(fst)
            pos = self.fst_off + len(fst)
            for path, off, length, idx in sorted(self.files, key=lambda e: (e[1], e[2])):
                pad = (4 - pos % 4) % 4
                out.write(b"\0" * pad)
                pos += pad
                struct.pack_into(">I", fst, idx * 12 + 4, pos)
                data = self.file_bytes(path)
                if len(data) != length:
                    raise IOError(f"short read for {path}")
                out.write(data)
                pos += length
            out.seek(self.fst_off)
            out.write(fst)
        os.replace(tmp, out_path)
        return out_path


def tgc_files(blob):
    """{path: bytes} of a GameCube TGC (embedded disc) blob."""
    if u32be(blob, 0) != 0xAE0F38A2:
        raise ValueError("not a TGC")
    fst_off, fst_size = u32be(blob, 0x10), u32be(blob, 0x14)
    file_area, virt = u32be(blob, 0x24), u32be(blob, 0x34)
    fst = blob[fst_off:fst_off + fst_size]
    out = {}
    for path, off, length, _ in parse_fst(fst):
        real = off - virt + file_area
        out[path] = blob[real:real + length]
    return out
