#include "libc64/math64.h"
#include "MSL_C/w_math.h"

f32 fatan2(f32 x, f32 y) {
    return atan2(x, y);
}

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
f32 game_fsqrt(f32 x) {
#else
f32 fsqrt(f32 x) {
#endif
    return sqrtf(x);
}

f32 facos(f32 x) {
    return acos(x);
}
