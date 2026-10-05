/* android_ansi_decls.h — forced into every game/PC/fixnes TU via
 * -include in CMakeLists.txt.
 *
 * The decomp calls bcopy/bzero/bcmp all over src/ without ever declaring
 * them (upstream built with gcc, which tolerates implicit declarations).
 * Clang 16+ makes implicit declarations an error. Bionic <strings.h> only
 * provides bcopy/bzero as FORTIFY macros, so TUs that include it get macro
 * call sites — this declaration is parsed first and matches bionic's
 * signature, so both can coexist in one TU. */
#ifndef AC_ANDROID_ANSI_DECLS_H
#define AC_ANDROID_ANSI_DECLS_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

int bcmp(const void* s1, const void* s2, size_t n);
void bcopy(const void* src, void* dst, size_t n);
void bzero(void* s, size_t n);

#ifdef __cplusplus
}
#endif

#endif /* AC_ANDROID_ANSI_DECLS_H */
