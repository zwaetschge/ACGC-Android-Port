#ifndef _DOLPHIN_CARDRDWR_H_
#define _DOLPHIN_CARDRDWR_H_

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
#ifndef TARGET_PC
long CARDGetXferredBytes(long chan);
#endif
#else
long CARDGetXferredBytes(long chan);
#endif

#endif // _DOLPHIN_CARDRDWR_H_
