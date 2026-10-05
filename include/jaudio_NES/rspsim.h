#ifndef RSPSIM_H
#define RSPSIM_H

#include "types.h"
#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
#include "PR/abi.h"
#else
#endif

#define RSPSIM_MODE_INIT 0
#define RSPSIM_MODE_MOVE 1

#ifdef __cplusplus
extern "C" {
#endif

#if defined(TARGET_PC) && __SIZEOF_POINTER__ == 8
extern s32 RspStart(Acmd* pTaskCmds, s32 allTasks);
extern void RspStart2(Acmd* pTaskCmds, s32 allTasks, s32 mode);
#else
extern s32 RspStart(u32* pTaskCmds, s32 allTasks);
extern void RspStart2(u32* pTaskCmds, s32 allTasks, s32 mode);
#endif

#ifdef __cplusplus
}
#endif

#endif
