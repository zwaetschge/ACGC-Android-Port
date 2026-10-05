#ifndef PC_TEXTURE_PACK_H
#define PC_TEXTURE_PACK_H

#include "pc_platform.h"

#ifdef __cplusplus
extern "C" {
#endif

void pc_texture_pack_init(void);
void pc_texture_pack_preload_all(void);
void pc_texture_pack_shutdown(void);

/* Returns GL texture ID for an HD replacement, or 0 if none found */
GLuint pc_texture_pack_lookup(const void* data, int data_size,
                              int w, int h, unsigned int fmt,
                              const void* tlut_data, int tlut_entries, int tlut_is_be,
                              int* out_w, int* out_h);

int pc_texture_pack_active(void);

/* Minification filter for HD replacements (uses their mip chain). */
GLenum pc_texture_pack_min_filter(unsigned int gx_filter);

#ifdef __cplusplus
}
#endif

#endif /* PC_TEXTURE_PACK_H */
