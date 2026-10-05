#ifndef _DOLPHIN_CARDCHECK_H_
#define _DOLPHIN_CARDCHECK_H_

s32 CARDCheckAsync(s32 chan, CARDCallback callback);
#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
#ifndef TARGET_PC
long CARDCheck(long chan);
#endif
#else
long CARDCheck(long chan);
#endif

#endif // _DOLPHIN_CARDCHECK_H_
