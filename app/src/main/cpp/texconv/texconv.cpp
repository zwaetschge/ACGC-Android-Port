// Texture-pack converter for Android (JNI: com.acpc.port.TexConv).
//
// Input:  Dolphin-format DDS (DX10 BC7 / BC1 / BC3 / RGBA8, legacy DXT1/DXT5).
// Output: DDS with a DX10 header carrying DXGI format 134 (ASTC 4x4) and a
//         full mip chain - the layout pc_texture_pack.c loads on GPUs that
//         only support ASTC.
#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "astcenc.h"

#define BCDEC_IMPLEMENTATION
#include "bcdec.h"

namespace {

constexpr uint32_t DXGI_RGBA8 = 28, DXGI_BGRA8 = 87, DXGI_BC1 = 71, DXGI_BC3 = 77, DXGI_BC7 = 98;
constexpr uint32_t DXGI_ASTC_4X4 = 134;

uint32_t rd32(const uint8_t* p) { uint32_t v; memcpy(&v, p, 4); return v; }
void wr32(uint8_t* p, uint32_t v) { memcpy(p, &v, 4); }

// One astcenc context per worker thread (contexts are not thread safe).
struct Encoder {
    astcenc_context* ctx = nullptr;
    float quality = -1;
    ~Encoder() { if (ctx) astcenc_context_free(ctx); }
    bool ensure(float q) {
        if (ctx && q == quality) return true;
        if (ctx) astcenc_context_free(ctx);
        ctx = nullptr;
        astcenc_config cfg;
        if (astcenc_config_init(ASTCENC_PRF_LDR, 4, 4, 1, q, 0, &cfg) != ASTCENC_SUCCESS) return false;
        if (astcenc_context_alloc(&cfg, 1, &ctx, nullptr) != ASTCENC_SUCCESS) return false;
        quality = q;
        return true;
    }
};
thread_local Encoder t_encoder;

// Decode the top level into RGBA8. Returns false for unsupported formats.
bool decode(const uint8_t* dds, size_t size, uint32_t& w, uint32_t& h, std::vector<uint8_t>& rgba) {
    if (size < 128 || memcmp(dds, "DDS ", 4) != 0) return false;
    h = rd32(dds + 12);
    w = rd32(dds + 16);
    if (!w || !h || w > 8192 || h > 8192) return false;
    uint32_t pf_flags = rd32(dds + 80), fourcc = rd32(dds + 84);
    size_t off = 128;
    uint32_t fmt = 0;
    if ((pf_flags & 4) && fourcc == 0x30315844) {  // "DX10"
        if (size < 148) return false;
        fmt = rd32(dds + 128);
        off = 148;
    } else if (pf_flags & 4) {
        if (fourcc == 0x31545844) fmt = DXGI_BC1;       // DXT1
        else if (fourcc == 0x35545844) fmt = DXGI_BC3;  // DXT5
        else return false;
    } else if (rd32(dds + 88) == 32) {
        fmt = DXGI_RGBA8;
    } else {
        return false;
    }

    rgba.assign(size_t(w) * h * 4, 0);
    const uint8_t* src = dds + off;
    size_t avail = size - off;
    if (fmt == DXGI_RGBA8 || fmt == DXGI_BGRA8) {
        if (avail < rgba.size()) return false;
        memcpy(rgba.data(), src, rgba.size());
        if (fmt == DXGI_BGRA8)
            for (size_t i = 0; i < rgba.size(); i += 4) std::swap(rgba[i], rgba[i + 2]);
        return true;
    }
    int block = fmt == DXGI_BC1 ? 8 : 16;
    if (fmt != DXGI_BC1 && fmt != DXGI_BC3 && fmt != DXGI_BC7) return false;
    uint32_t bx = (w + 3) / 4, by = (h + 3) / 4;
    if (avail < size_t(bx) * by * block) return false;
    uint8_t tile[4 * 4 * 4];
    for (uint32_t y = 0; y < by; y++) {
        for (uint32_t x = 0; x < bx; x++, src += block) {
            if (fmt == DXGI_BC7) bcdec_bc7(src, tile, 16);
            else if (fmt == DXGI_BC3) bcdec_bc3(src, tile, 16);
            else bcdec_bc1(src, tile, 16);
            for (uint32_t ty = 0; ty < 4 && y * 4 + ty < h; ty++) {
                uint32_t n = (w - x * 4 < 4 ? w - x * 4 : 4) * 4;
                memcpy(&rgba[(size_t(y * 4 + ty) * w + x * 4) * 4], tile + ty * 16, n);
            }
        }
    }
    return true;
}

// 2x2 box filter (odd edges clamp), RGBA8.
void downsample(const std::vector<uint8_t>& src, uint32_t w, uint32_t h,
                std::vector<uint8_t>& dst, uint32_t nw, uint32_t nh) {
    dst.resize(size_t(nw) * nh * 4);
    for (uint32_t y = 0; y < nh; y++) {
        uint32_t y0 = y * 2 < h ? y * 2 : h - 1, y1 = y * 2 + 1 < h ? y * 2 + 1 : y0;
        for (uint32_t x = 0; x < nw; x++) {
            uint32_t x0 = x * 2 < w ? x * 2 : w - 1, x1 = x * 2 + 1 < w ? x * 2 + 1 : x0;
            for (int c = 0; c < 4; c++) {
                uint32_t s = src[(size_t(y0) * w + x0) * 4 + c] + src[(size_t(y0) * w + x1) * 4 + c] +
                             src[(size_t(y1) * w + x0) * 4 + c] + src[(size_t(y1) * w + x1) * 4 + c];
                dst[(size_t(y) * nw + x) * 4 + c] = uint8_t((s + 2) / 4);
            }
        }
    }
}

bool encode_level(std::vector<uint8_t>& rgba, uint32_t w, uint32_t h, std::vector<uint8_t>& out) {
    size_t len = size_t((w + 3) / 4) * ((h + 3) / 4) * 16;
    size_t at = out.size();
    out.resize(at + len);
    void* slices[1] = {rgba.data()};
    astcenc_image img{w, h, 1, ASTCENC_TYPE_U8, slices};
    astcenc_swizzle swz{ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};
    astcenc_error e = astcenc_compress_image(t_encoder.ctx, &img, &swz, out.data() + at, len, 0);
    astcenc_compress_reset(t_encoder.ctx);
    return e == ASTCENC_SUCCESS;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_acpc_port_TexConv_convert(JNIEnv* env, jclass, jbyteArray input, jint length,
                                   jstring outPath, jfloat quality) {
    jbyte* raw = env->GetByteArrayElements(input, nullptr);
    if (!raw) return -1;
    std::vector<uint8_t> dds(reinterpret_cast<uint8_t*>(raw), reinterpret_cast<uint8_t*>(raw) + length);
    env->ReleaseByteArrayElements(input, raw, JNI_ABORT);

    uint32_t w, h;
    std::vector<uint8_t> level;
    if (!decode(dds.data(), dds.size(), w, h, level)) return -2;
    if (!t_encoder.ensure(quality)) return -3;

    std::vector<uint8_t> payload, next;
    uint32_t lw = w, lh = h, levels = 0;
    for (;;) {
        if (!encode_level(level, lw, lh, payload)) return -4;
        levels++;
        if (lw == 1 && lh == 1) break;
        uint32_t nw = lw > 1 ? lw / 2 : 1, nh = lh > 1 ? lh / 2 : 1;
        downsample(level, lw, lh, next, nw, nh);
        level.swap(next);
        lw = nw;
        lh = nh;
    }

    uint8_t hdr[148] = {0};
    memcpy(hdr, "DDS ", 4);
    wr32(hdr + 4, 124);
    wr32(hdr + 8, 0x1 | 0x2 | 0x4 | 0x1000 | 0x20000);  // caps|height|width|pixelformat|mipmapcount
    wr32(hdr + 12, h);
    wr32(hdr + 16, w);
    wr32(hdr + 28, levels);
    wr32(hdr + 76, 32);
    wr32(hdr + 80, 4);           // DDPF_FOURCC
    memcpy(hdr + 84, "DX10", 4);
    wr32(hdr + 108, 0x1000 | 0x400000 | 0x8);  // texture | mipmap | complex
    wr32(hdr + 128, DXGI_ASTC_4X4);
    wr32(hdr + 132, 3);          // TEXTURE2D
    wr32(hdr + 140, 1);          // array size

    const char* path = env->GetStringUTFChars(outPath, nullptr);
    std::string tmp = std::string(path) + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    bool ok = f && fwrite(hdr, 1, sizeof(hdr), f) == sizeof(hdr) &&
              fwrite(payload.data(), 1, payload.size(), f) == payload.size();
    if (f) ok = (fclose(f) == 0) && ok;
    if (ok) ok = rename(tmp.c_str(), path) == 0;
    else remove(tmp.c_str());
    env->ReleaseStringUTFChars(outPath, path);
    return ok ? int(levels) : -5;
}
