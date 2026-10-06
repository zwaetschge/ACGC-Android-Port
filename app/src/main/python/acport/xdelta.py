"""Pure-Python VCDIFF (RFC 3284) decoder with the xdelta3 extensions used by
the Animal Crossing Deluxe patches: LZMA secondary compression (one xz stream
per section that continues across windows), application header and per-window
Adler-32 checksums. Source windows only (xdelta3 never emits VCD_TARGET)."""

import lzma
import zlib

VCD_SECONDARY, VCD_CODETABLE, VCD_APPHEADER = 1, 2, 4
VCD_SOURCE, VCD_TARGET, VCD_ADLER32 = 1, 2, 4
VCD_DATACOMP, VCD_INSTCOMP, VCD_ADDRCOMP = 1, 2, 4
LZMA_ID = 2

NOOP, ADD, RUN, COPY = 0, 1, 2, 3
S_NEAR, S_SAME = 4, 3


def _default_code_table():
    t = [((RUN, 0, 0), (NOOP, 0, 0))]
    t += [((ADD, s, 0), (NOOP, 0, 0)) for s in range(18)]
    for m in range(9):
        t.append(((COPY, 0, m), (NOOP, 0, 0)))
        t += [((COPY, s, m), (NOOP, 0, 0)) for s in range(4, 19)]
    for m in range(6):
        t += [((ADD, a, 0), (COPY, c, m)) for a in range(1, 5) for c in range(4, 7)]
    for m in range(6, 9):
        t += [((ADD, a, 0), (COPY, 4, m)) for a in range(1, 5)]
    t += [((COPY, 4, m), (ADD, 1, 0)) for m in range(9)]
    assert len(t) == 256
    return t


CODE_TABLE = _default_code_table()


class PatchError(Exception):
    pass


class _Reader:
    def __init__(self, buf, pos=0):
        self.buf = buf
        self.pos = pos

    def byte(self):
        b = self.buf[self.pos]
        self.pos += 1
        return b

    def varint(self):
        v = 0
        while True:
            b = self.buf[self.pos]
            self.pos += 1
            v = (v << 7) | (b & 0x7F)
            if not b & 0x80:
                return v

    def take(self, n):
        out = self.buf[self.pos:self.pos + n]
        if len(out) != n:
            raise PatchError("truncated patch")
        self.pos += n
        return out


def apply(source_path, patch_path, out_path, progress=None):
    """Writes the patched file; returns its size. progress(done, total) per window.
    source_path may also be an open file descriptor (it is not closed)."""
    with open(patch_path, "rb") as f:
        patch = f.read()
    r = _Reader(patch)
    if r.take(4) != b"\xd6\xc3\xc4\x00":
        raise PatchError("not a VCDIFF/xdelta3 patch")
    hdr = r.byte()
    if hdr & VCD_CODETABLE:
        raise PatchError("custom code tables are not supported")
    sec_id = r.byte() if hdr & VCD_SECONDARY else 0
    if sec_id not in (0, LZMA_ID):
        raise PatchError("unsupported secondary compressor %d" % sec_id)
    if hdr & VCD_APPHEADER:
        r.take(r.varint())
    # one persistent xz stream per section kind (data, inst, addr)
    sec = [lzma.LZMADecompressor(lzma.FORMAT_XZ) for _ in range(3)] if sec_id else None

    written = 0
    with open(source_path, "rb", closefd=not isinstance(source_path, int)) as src, \
            open(out_path, "wb") as out:
        while r.pos < len(patch):
            win_ind = r.byte()
            if win_ind & VCD_TARGET:
                raise PatchError("VCD_TARGET windows are not supported")
            seg_len = seg_pos = 0
            if win_ind & VCD_SOURCE:
                seg_len = r.varint()
                seg_pos = r.varint()
            r.varint()  # delta encoding length
            tgt_len = r.varint()
            del_ind = r.byte()
            lens = (r.varint(), r.varint(), r.varint())
            cksum = int.from_bytes(r.take(4), "big") if win_ind & VCD_ADLER32 else None
            sections = []
            for i, n in enumerate(lens):
                raw = r.take(n)
                if del_ind & (1 << i):
                    if sec is None:
                        raise PatchError("compressed section without secondary compressor")
                    sr = _Reader(raw)
                    size = sr.varint()
                    raw = sec[i].decompress(raw[sr.pos:], max_length=size)
                    if len(raw) != size:
                        raise PatchError("secondary decoder short output")
                sections.append(raw)
            data, inst, addr = sections

            source = b""
            if seg_len:
                src.seek(seg_pos)
                source = src.read(seg_len)
                if len(source) != seg_len:
                    raise PatchError("source image too small")
            target = bytearray()
            near = [0] * S_NEAR
            same = [0] * (S_SAME * 256)
            next_near = 0
            dp = ap = 0
            ir = _Reader(inst)

            while ir.pos < len(inst):
                for typ, size, mode in CODE_TABLE[ir.byte()]:
                    if typ == NOOP:
                        continue
                    if size == 0:
                        size = ir.varint()
                    if typ == ADD:
                        target += data[dp:dp + size]
                        dp += size
                    elif typ == RUN:
                        target += data[dp:dp + 1] * size
                        dp += 1
                    else:
                        here = seg_len + len(target)
                        if mode == 0 or mode == 1 or mode < 2 + S_NEAR:
                            a = _Reader(addr, ap)
                            v = a.varint()
                            ap = a.pos
                            if mode == 0:
                                ad = v
                            elif mode == 1:
                                ad = here - v
                            else:
                                ad = near[mode - 2] + v
                        else:
                            ad = same[(mode - 2 - S_NEAR) * 256 + addr[ap]]
                            ap += 1
                        near[next_near] = ad
                        next_near = (next_near + 1) % S_NEAR
                        same[ad % (S_SAME * 256)] = ad
                        if ad + size <= seg_len:
                            target += source[ad:ad + size]
                        else:
                            if ad < seg_len:  # straddles source and target
                                part = seg_len - ad
                                target += source[ad:]
                                size -= part
                                ad = seg_len
                            t = ad - seg_len
                            if t + size <= len(target):
                                target += target[t:t + size]
                            else:  # overlapping self-copy repeats the pattern
                                for k in range(size):
                                    target.append(target[t + k])
            if len(target) != tgt_len:
                raise PatchError("window size mismatch (%d != %d)" % (len(target), tgt_len))
            if cksum is not None and (zlib.adler32(target) & 0xFFFFFFFF) != cksum:
                raise PatchError("checksum mismatch, wrong source image?")
            out.write(target)
            written += len(target)
            if progress:
                progress(r.pos, len(patch))
    return written
