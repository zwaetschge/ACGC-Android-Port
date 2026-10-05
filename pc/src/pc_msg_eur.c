/* pc_msg_eur.c - Runtime EUR BMG message loader and USD-format rewriter.
 *
 * Loads translations/<lang>/msg.bin (Nintendo BMG, extracted from the EUR disc)
 * and translates it to USA control-code format so the existing msg dispatcher
 * can handle it unchanged.
 *
 * Activated only when settings.ini [Localization] language is one of:
 *   en-EU, fr-FR, de-DE, it-IT, es-ES
 *
 * Source file expected at: translations/<lang>/msg.bin
 * (copied there by extract_translations.sh)
 */

#ifdef TARGET_PC
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "types.h"
#include "include/m_font.h"
#include "include/m_msg.h"
#include "pc/include/pc_bswap.h"
#include "pc/include/pc_msg_eur.h"
#include "pc/include/pc_settings.h"

/* ── State ─────────────────────────────────────────────────────────────── */

static int   s_tried    = 0;  /* 1 after first load attempt */
static int   s_active   = 0;  /* 1 when EUR path is live    */

static u8*  s_data      = NULL;
static u32  s_data_size = 0;

static u32* s_table     = NULL;  /* end-offset per entry (native endian) */
static u32  s_table_cnt = 0;

/* ── Language → TGC-code map ─────────────────────────────────────────── */

static const char* eur_lang_code(const char* lang)
{
    if (!lang) return NULL;
    if (strcmp(lang, "en-EU") == 0) return "Eng";
    if (strcmp(lang, "fr-FR") == 0) return "Frn";
    if (strcmp(lang, "de-DE") == 0) return "Gmn";
    if (strcmp(lang, "it-IT") == 0) return "Itl";
    if (strcmp(lang, "es-ES") == 0) return "Spn";
    return NULL;
}

/* ── Gender-switch translator ─────────────────────────────────────────── */
/*
 * EUR gender-switch payload: 13 00 14 <masc_run…> FE <fem_run…>
 * The two runs share a common prefix; only the final byte differs.
 * Emits: <common_prefix_bytes>  7F GENDER_CHAR <masc_tail> <fem_tail>
 * Falls back to the masculine form if no FE separator is found.
 */
u32 pc_msg_eur_translate_gender_cmd(const u8* p, u32 n, u8* out)
{
    /* Payload must start with 13 00 14 and contain at least one body byte. */
    if (n < 4 || p[0] != 0x13 || p[1] != 0x00 || p[2] != 0x14)
        return 0;

    const u8* body     = p + 3;   /* bytes after 13 00 14 */
    u32       body_len = n - 3;

    /* Find FE separator. */
    u32 fe_pos = body_len;  /* default: no separator (only masculine) */
    u32 i;
    for (i = 0; i < body_len; i++) {
        if (body[i] == 0xFE) { fe_pos = i; break; }
    }

    const u8* masc     = body;
    u32       masc_len = fe_pos;
    const u8* fem      = (fe_pos + 1 < body_len) ? body + fe_pos + 1 : NULL;
    u32       fem_len  = fem ? (body_len - fe_pos - 1) : 0;

    if (fem == NULL || masc_len == 0) {
        /* No gender distinction — emit masculine text verbatim. */
        if (masc_len == 0) return 0;
        memcpy(out, masc, masc_len);
        return masc_len;
    }

    /* Find common prefix length. */
    u32 prefix_len = 0;
    u32 cmp_len = masc_len < fem_len ? masc_len : fem_len;
    while (prefix_len < cmp_len && masc[prefix_len] == fem[prefix_len])
        prefix_len++;

    /* Emit: prefix + 7F GENDER_CHAR masc_tail fem_tail */
    u32 written = 0;
    memcpy(out + written, masc, prefix_len);
    written += prefix_len;
    out[written++] = 0x7F;
    out[written++] = (u8)mFont_CONT_CODE_GENDER_CHAR;
    out[written++] = (masc_len > prefix_len) ? masc[prefix_len] : 0x00;
    out[written++] = (fem_len  > prefix_len) ? fem[prefix_len]  : 0x00;
    return written;
}

/* ── EUR control-code → USA bytes ────────────────────────────────────── */

/*
 * Translate one EUR control sequence to USA format.
 * p   = payload bytes (after the 0x80 <total_size> header)
 * n   = payload length (= total_size - 2)
 * out = output buffer; caller guarantees >= 32 free bytes
 * Returns bytes written, or 0 if unrecognised.
 */
#include "pc/src/pc_msg_eur_codes.inc"

/* ── BMG parser (big-endian, GC format) ──────────────────────────────── */

static int parse_bmg(const u8* data, u32 len,
                     u32** out_offsets, u32* out_n,
                     const u8** out_dat1, u32* out_dat1_len)
{
    if (len < 32 || memcmp(data, "MESGbmg1", 8) != 0) {
        fprintf(stderr, "[pc_msg_eur] bad BMG magic\n");
        return 0;
    }

    u32 num_blocks;
    memcpy(&num_blocks, data + 12, 4);
    num_blocks = pc_bswap32(num_blocks);

    u32 pos = 32;
    u32* offsets  = NULL;
    u32  n_entries = 0;
    const u8* dat1 = NULL;
    u32  dat1_len  = 0;

    u32 b;
    for (b = 0; b < num_blocks && pos + 8 <= len; b++) {
        u32 block_size;
        memcpy(&block_size, data + pos + 4, 4);
        block_size = pc_bswap32(block_size);

        if (memcmp(data + pos, "INF1", 4) == 0 && pos + 16 <= len) {
            u16 num_e, entry_size;
            memcpy(&num_e,      data + pos + 8,  2);
            memcpy(&entry_size, data + pos + 10, 2);
            num_e      = (u16)((num_e      >> 8) | (num_e      << 8));
            entry_size = (u16)((entry_size >> 8) | (entry_size << 8));

            n_entries = num_e;
            offsets = (u32*)malloc(n_entries * sizeof(u32));
            if (!offsets) { fprintf(stderr, "[pc_msg_eur] OOM\n"); return 0; }

            u32 entries_start = pos + 16;
            u32 i;
            for (i = 0; i < n_entries; i++) {
                u32 off = entries_start + i * entry_size;
                if (off + 4 > len) { offsets[i] = 0; continue; }
                u32 v; memcpy(&v, data + off, 4);
                offsets[i] = pc_bswap32(v);
            }
        } else if (memcmp(data + pos, "DAT1", 4) == 0) {
            dat1     = data + pos + 8;
            dat1_len = block_size - 8;
        }

        pos += block_size;
    }

    if (!offsets || !dat1) {
        fprintf(stderr, "[pc_msg_eur] BMG missing INF1 or DAT1\n");
        free(offsets);
        return 0;
    }

    *out_offsets  = offsets;
    *out_n        = n_entries;
    *out_dat1     = dat1;
    *out_dat1_len = dat1_len;
    return 1;
}

/* ── Rewriter ─────────────────────────────────────────────────────────── */

#define EUR_ESCAPE 0x80

static int translate_entries(const u32* offsets, u32 n,
                             const u8* dat1, u32 dat1_len)
{
    /* Upper-bound: rewritten data can be at most ~2× source size. */
    u32 data_cap = dat1_len * 2 + 4096;
    s_data = (u8*)malloc(data_cap);
    if (!s_data) { fprintf(stderr, "[pc_msg_eur] OOM\n"); return 0; }

    s_table = (u32*)malloc(n * sizeof(u32));
    if (!s_table) { free(s_data); s_data = NULL; fprintf(stderr, "[pc_msg_eur] OOM\n"); return 0; }

    u32 write_pos = 0;
    u32 i;
    for (i = 0; i < n; i++) {
        u32 src_start = offsets[i];
        u32 src_end   = (i + 1 < n) ? offsets[i + 1] : dat1_len;

        if (src_end <= src_start) {
            s_table[i] = write_pos;
            continue;
        }

        u32 entry_start = write_pos;
        u32 j = src_start;

        while (j < src_end) {
            /* Grow data buffer if needed. */
            if (write_pos + 64 > data_cap) {
                data_cap *= 2;
                u8* tmp = (u8*)realloc(s_data, data_cap);
                if (!tmp) {
                    fprintf(stderr, "[pc_msg_eur] OOM during rewrite\n");
                    free(s_data); free(s_table);
                    s_data = NULL; s_table = NULL;
                    return 0;
                }
                s_data = tmp;
            }

            u8 byte = dat1[j];

            if (byte == EUR_ESCAPE) {
                if (j + 1 >= src_end) { j++; continue; }
                u32 total_size = dat1[j + 1];
                if (total_size < 2) total_size = 2;

                const u8* payload = dat1 + j + 2;
                u32       pay_len = (j + total_size <= src_end) ? (total_size - 2) : 0;

                u8  tmp_out[64];
                u32 written = pc_msg_eur_translate_cmd(payload, pay_len, tmp_out);

                if (written > 0) {
                    /* Per-entry size cap. */
                    if (write_pos - entry_start + written > mMsg_MSG_BUF_MAX) {
                        fprintf(stderr, "[pc_msg_eur] entry %u exceeds MSG_BUF_MAX, truncating\n", i);
                        break;
                    }
                    memcpy(s_data + write_pos, tmp_out, written);
                    write_pos += written;
                }
                /* else: unrecognised payload — log once and skip */
                if (written == 0) {
#ifdef PC_MSG_EUR_LOG_UNKNOWN
                    {
                        static u32 s_logged_n = 0;
                        u32 already = 0, li;
                        static u8 s_logged[64][8];
                        static u8 s_logged_len[64];
                        for (li = 0; li < s_logged_n && li < 64; li++) {
                            if (pay_len == s_logged_len[li] &&
                                memcmp(payload, s_logged[li], pay_len < 8 ? pay_len : 8) == 0) {
                                already = 1; break;
                            }
                        }
                        if (!already && s_logged_n < 64) {
                            u32 cplen = pay_len < 8 ? pay_len : 8;
                            memcpy(s_logged[s_logged_n], payload, cplen);
                            s_logged_len[s_logged_n] = (u8)cplen;
                            s_logged_n++;
                            {
                                u32 pi;
                                fprintf(stderr, "[pc_msg_eur] unknown payload entry %u off %u: ", i, j - src_start);
                                for (pi = 0; pi < pay_len; pi++)
                                    fprintf(stderr, "%02X ", payload[pi]);
                                fprintf(stderr, "\n");
                            }
                        }
                    }
#endif /* PC_MSG_EUR_LOG_UNKNOWN */
                }

                j += total_size;
            } else {
                /* Per-entry size cap. */
                if (write_pos - entry_start + 1 > mMsg_MSG_BUF_MAX) {
                    fprintf(stderr, "[pc_msg_eur] entry %u exceeds MSG_BUF_MAX, truncating\n", i);
                    break;
                }
                s_data[write_pos++] = byte;
                j++;
            }
        }

        s_table[i] = write_pos;
    }

    s_data_size = write_pos;
    s_table_cnt = n;
    return 1;
}

/* ── Public API ───────────────────────────────────────────────────────── */

void pc_msg_eur_reset(void)
{
    s_tried  = 0;
    s_active = 0;
    if (s_data)  { free(s_data);  s_data  = NULL; s_data_size = 0; }
    if (s_table) { free(s_table); s_table = NULL; s_table_cnt = 0; }
}

int pc_msg_eur_is_active(void)
{
    return s_active;
}

int pc_msg_eur_ensure_loaded(void)
{
    if (s_tried) return s_active;

    const char* lang = pc_settings_get_language();
    const char* code = eur_lang_code(lang);
    if (!code) return 0;  /* not EUR locale or settings not loaded yet — allow retry */
    s_tried = 1;  /* EUR language confirmed; commit to one load attempt */

    char path[256];
    snprintf(path, sizeof(path), "%s/%s/msg.bin", pc_settings_get_translations_dir(), lang);

    FILE* f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "[pc_msg_eur] msg.bin not found: %s\n", path);
        return 0;
    }

    fseek(f, 0, SEEK_END);
    long fsize = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (fsize <= 0) { fclose(f); fprintf(stderr, "[pc_msg_eur] empty: %s\n", path); return 0; }

    u8* raw = (u8*)malloc((u32)fsize);
    if (!raw) { fclose(f); fprintf(stderr, "[pc_msg_eur] OOM\n"); return 0; }
    if (fread(raw, 1, (u32)fsize, f) != (size_t)fsize) {
        fclose(f); free(raw);
        fprintf(stderr, "[pc_msg_eur] read error: %s\n", path);
        return 0;
    }
    fclose(f);

    u32*       offsets  = NULL;
    u32        n        = 0;
    const u8*  dat1     = NULL;
    u32        dat1_len = 0;

    fprintf(stderr, "[pc_msg_eur] opened %s (%ld bytes)\n", path, fsize);

    if (!parse_bmg(raw, (u32)fsize, &offsets, &n, &dat1, &dat1_len)) {
        fprintf(stderr, "[pc_msg_eur] parse_bmg failed\n");
        free(raw);
        return 0;
    }
    fprintf(stderr, "[pc_msg_eur] parse_bmg ok: %u entries, dat1_len=%u\n", n, dat1_len);

    int ok = translate_entries(offsets, n, dat1, dat1_len);
    free(offsets);
    free(raw);

    if (!ok) { fprintf(stderr, "[pc_msg_eur] translate_entries failed\n"); return 0; }

    s_active = 1;
    fprintf(stderr, "[pc_msg_eur] loaded %s (%u entries, %u bytes out)\n",
            path, s_table_cnt, s_data_size);
    return 1;
}

const u8* pc_msg_eur_get_data(u32* out_size)
{
    if (out_size) *out_size = s_data_size;
    return s_data;
}

const u32* pc_msg_eur_get_table(u32* out_count)
{
    if (out_count) *out_count = s_table_cnt;
    return s_table;
}

#endif /* TARGET_PC */
