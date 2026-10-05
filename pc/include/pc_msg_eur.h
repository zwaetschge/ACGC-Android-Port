#pragma once
#ifdef TARGET_PC

#include "pc/include/pc_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Returns non-zero when the active language is an EUR locale and msg.bin was
 * loaded successfully.  Cached after first call to pc_msg_eur_ensure_loaded(). */
int pc_msg_eur_is_active(void);

/* Reset state and free buffers.  Must be called before ensure_loaded() on each
 * archive reload so that a language change or late settings load takes effect. */
void pc_msg_eur_reset(void);

/* Parse and translate translations/<lang>/msg.bin into a USA-format buffer.
 * Returns 1 on success, 0 on failure (missing file, bad magic, wrong language). */
int pc_msg_eur_ensure_loaded(void);

/* Accessors — only valid after a successful ensure_loaded(). */
const u8* pc_msg_eur_get_data(u32* out_size);   /* rewritten USA-format payload  */
const u32* pc_msg_eur_get_table(u32* out_count); /* end-offset per entry (BE u32) */

/* Called from pc_msg_eur_codes.inc for the gender-switch payload
 * (first byte 0x13):  EUR "13 00 14 <masc> FE <fem>" → USA "7F 6B m_tail f_tail". */
u32 pc_msg_eur_translate_gender_cmd(const u8* p, u32 n, u8* out);

#ifdef __cplusplus
}
#endif

#endif /* TARGET_PC */
