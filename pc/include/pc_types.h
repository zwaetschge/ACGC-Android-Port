#ifndef PC_TYPES_H
#define PC_TYPES_H

/**
 * pc_types.h - Common type definitions for the PC port layer.
 * Included automatically via pc_platform.h.
 */

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
#include <stdint.h>
#else
#endif
typedef unsigned char      u8;
typedef unsigned short     u16;
#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
typedef uint32_t           u32;
typedef uint64_t           u64;
#else
typedef unsigned long      u32;
typedef unsigned long long u64;
#endif
typedef signed char        s8;
typedef signed short       s16;
#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
typedef int32_t            s32;
typedef int64_t            s64;
#else
typedef signed long        s32;
typedef signed long long   s64;
#endif
typedef float              f32;
typedef double             f64;
typedef int                BOOL;

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif

#endif /* PC_TYPES_H */
