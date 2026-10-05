#ifndef _DOLPHIN_CARDFORMAT_H_
#define _DOLPHIN_CARDFORMAT_H_

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
#ifndef TARGET_PC
long CARDFormat(long chan);
#endif
#else
long CARDFormat(long chan);
#endif

#endif // _DOLPHIN_CARDFORMAT_H_
