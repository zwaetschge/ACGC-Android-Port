/* glad/gl.h — Android shim.
 *
 * The PC port includes <glad/gl.h> (glad2 desktop GL 3.3 loader). On Android
 * we compile against system OpenGL ES 3 instead. The subset of GL the port
 * uses is ES 3 compatible; only the loader call and two desktop-only entry
 * points need mapping.
 */
#ifndef GLAD_GL_H_ANDROID_SHIM
#define GLAD_GL_H_ANDROID_SHIM

#include <GLES3/gl3.h>

/* glad2 loader entry — the PC port calls this once with SDL_GL_GetProcAddress.
 * On Android the GL functions are statically linked, nothing to load. */
typedef void* (*GLADloadfunc)(const char* name);
static inline int gladLoadGL(GLADloadfunc proc) { (void)proc; return 1; }

/* Desktop-only entry points -> ES equivalents */
#define glClearDepth(depth) glClearDepthf((GLfloat)(depth))
#define glDepthRange(near, far) glDepthRangef((GLfloat)(near), (GLfloat)(far))
/* glPointSize does not exist in OpenGL ES 3 (points are always 1px). */
#define glPointSize(size) ((void)0)

/* The port calls uniform uploads through the glad2 prefixed names. */
#define glad_glUniform1f glUniform1f
#define glad_glUniform1i glUniform1i
#define glad_glUniform1iv glUniform1iv
#define glad_glUniform2f glUniform2f
#define glad_glUniform2i glUniform2i
#define glad_glUniform2iv glUniform2iv
#define glad_glUniform3f glUniform3f
#define glad_glUniform3fv glUniform3fv
#define glad_glUniform3i glUniform3i
#define glad_glUniform3iv glUniform3iv
#define glad_glUniform4f glUniform4f
#define glad_glUniform4fv glUniform4fv
#define glad_glUniform4i glUniform4i
#define glad_glUniform4iv glUniform4iv
#define glad_glUniformMatrix3fv glUniformMatrix3fv
#define glad_glUniformMatrix4fv glUniformMatrix4fv

/* Route shader compilation through the GLSL rewriter in android_glue.c
 * (#version 330 core -> #version 300 es + precision qualifiers). */
int ac_glShaderSource(GLuint shader, GLsizei count,
                      const GLchar* const* string, const GLint* length);
#define glShaderSource(shader, count, string, length) \
    ac_glShaderSource((shader), (count), (string), (length))

#endif /* GLAD_GL_H_ANDROID_SHIM */
