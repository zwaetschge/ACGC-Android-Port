/* android_glue.h — Android platform bootstrap for the AC PC port */
#ifndef AC_ANDROID_GLUE_H
#define AC_ANDROID_GLUE_H

#ifdef __cplusplus
extern "C" {
#endif

/* Called at the top of main(): switches into the app's private storage so
 * all relative paths (settings.ini, save/, rom/, shaders/) keep working. */
void android_bootstrap(void);

/* GLSL 330 core -> 300 es rewrite, installed as glShaderSource shim. */
int ac_glShaderSource(GLuint shader, GLsizei count,
                      const GLchar* const* string, const GLint* length);

#ifdef __cplusplus
}
#endif

#endif
