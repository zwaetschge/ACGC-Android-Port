#ifndef SYS_UCODE_H
#define SYS_UCODE_H

#include "types.h"
#include "PR/mbi.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ucode_info_s {
    int type;
    void* ucode_p;
} ucode_info;

#define UCODE_TYPE_NONE 0
#define UCODE_TYPE_POLY_TEXT 1
#define UCODE_TYPE_POLY_DATA 2
#define UCODE_TYPE_SPRITE_TEXT 3
#define UCODE_TYPE_SPRITE_DATA 4

#define SP_UCODE_DATA_SIZE 0x800

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
extern s64 gspF3DZEX2_NoN_PosLight_fifoDataStart[];
extern s64 gspF3DZEX2_NoN_PosLight_fifoTextStart[];
#else
extern long long int gspF3DZEX2_NoN_PosLight_fifoDataStart[];
extern long long int gspF3DZEX2_NoN_PosLight_fifoTextStart[];
#endif

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
extern u64 gspS2DEX2_fifoDataStart[];
extern u64 gspS2DEX2_fifoTextStart[];
#else
extern unsigned long long gspS2DEX2_fifoDataStart[];
extern unsigned long long gspS2DEX2_fifoTextStart[];
#endif

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
extern s64* ucode_GetPolyTextStart();
extern s64* ucode_GetPolyDataStart();
extern u64* ucode_GetSpriteTextStart();
extern u64* ucode_GetSpriteDataStart();
#else
extern long long int* ucode_GetPolyTextStart();
extern long long int* ucode_GetPolyDataStart();
extern unsigned long long* ucode_GetSpriteTextStart();
extern unsigned long long* ucode_GetSpriteDataStart();
#endif

#ifdef __cplusplus
};
#endif

#endif
