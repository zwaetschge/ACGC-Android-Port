#ifndef _DOLPHIN_CARDWRITE_H_
#define _DOLPHIN_CARDWRITE_H_

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
#ifndef TARGET_PC
long CARDWriteAsync(struct CARDFileInfo * fileInfo, const void * buf, long length, long offset, void (* callback)(long, long));
long CARDWrite(struct CARDFileInfo * fileInfo, const void * buf, long length, long offset);
#endif
#else
long CARDWriteAsync(struct CARDFileInfo * fileInfo, const void * buf, long length, long offset, void (* callback)(long, long));
long CARDWrite(struct CARDFileInfo * fileInfo, const void * buf, long length, long offset);
#endif

#endif // _DOLPHIN_CARDWRITE_H_
