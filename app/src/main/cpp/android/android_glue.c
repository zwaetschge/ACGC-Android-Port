/* android_glue.c — Android platform glue for the AC PC port.
 *
 * - chdir() into the app's internal storage directory so every relative
 *   path the port uses (settings.ini, keybindings.ini, rom/, save/,
 *   texture_pack/, shaders/) works unchanged.
 * - redirect stdout/stderr into files (debug builds: adb shell run-as
 *   com.acpc.port cat files/stdout.log).
 * - rewrite desktop GLSL (#version 330 core) to OpenGL ES 3 GLSL
 *   (#version 300 es + precision qualifiers) before compilation.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <SDL.h>
#include <SDL_system.h>
#include <GLES3/gl3.h>

#include "android_glue.h"

static void ensure_dir(const char* path) {
    mkdir(path, 0777);
}

void android_bootstrap(void) {
    const char* base = SDL_AndroidGetInternalStoragePath();
    if (base) {
        chdir(base);
        ensure_dir("rom");
        ensure_dir("save");
        ensure_dir("save/card_a");
        ensure_dir("save/card_b");
        ensure_dir("texture_pack");
        ensure_dir("shaders");
    }

    /* keep printf output available for debugging */
    freopen("stdout.log", "w", stdout);
    freopen("stderr.log", "w", stderr);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    printf("[AC/Android] bootstrap done (cwd=%s)\n", base ? base : "?");
}

/* --- GLSL desktop -> ES rewrite ------------------------------------- */

static int is_version_line(const char* p) {
    return strncmp(p, "#version", 8) == 0;
}

/* Replace the "#version 3xx ..." line with an ES 3.0 header and inject the
 * precision qualifiers ES requires. Returns a malloc'd string. */
static char* rewrite_glsl(const char* src) {
    const char* nl = strchr(src, '\n');
    size_t head = 0;
    char prefix[256];

    if (nl && is_version_line(src)) {
        head = (size_t)(nl - src) + 1; /* skip the whole version line */
    }

    snprintf(prefix, sizeof(prefix),
             "#version 300 es\n"
             "precision highp float;\n"
             "precision highp int;\n"
             "precision highp sampler2D;\n");

    size_t len = strlen(prefix) + strlen(src + head) + 1;
    char* out = (char*)malloc(len);
    if (!out) return NULL;
    memcpy(out, prefix, strlen(prefix));
    strcpy(out + strlen(prefix), src + head);
    return out;
}

int ac_glShaderSource(GLuint shader, GLsizei count,
                      const GLchar* const* string, const GLint* length) {
    if (count <= 0 || !string) return -1;

    /* concatenate sources */
    size_t total = 1;
    for (GLsizei i = 0; i < count; i++) {
        total += length && length[i] >= 0 ? (size_t)length[i] : strlen(string[i]);
    }
    char* src = (char*)malloc(total);
    if (!src) return -1;
    size_t off = 0;
    for (GLsizei i = 0; i < count; i++) {
        size_t n = length && length[i] >= 0 ? (size_t)length[i] : strlen(string[i]);
        memcpy(src + off, string[i], n);
        off += n;
    }
    src[off] = '\0';

    char* rewritten = rewrite_glsl(src);
    free(src);
    if (!rewritten) return -1;

    const GLchar* rw = rewritten;
    glShaderSource(shader, 1, &rw, NULL); /* real ES entry, not the macro */

    /* Compilation happens later via the port's own glCompileShader() call;
     * checking GL_COMPILE_STATUS here would always read FALSE (never compiled
     * yet) with an empty info log — a false-positive failure dump. */
    free(rewritten);
    return 0;
}

/* ---- On-screen touch controls (com.acpc.port.TouchControlsView) ----
 * Written on the Android UI thread, read by PADRead on the game thread. */
#include <jni.h>

volatile int g_android_touch_buttons = 0;
volatile int g_android_touch_stick_x = 0;
volatile int g_android_touch_stick_y = 0;
volatile int g_android_touch_cstick_x = 0;
volatile int g_android_touch_cstick_y = 0;

JNIEXPORT void JNICALL Java_com_acpc_port_GameActivity_nativeTouchPad(
    JNIEnv* env, jclass cls, jint buttons, jint sx, jint sy, jint cx, jint cy) {
    (void)env; (void)cls;
    g_android_touch_buttons = buttons;
    g_android_touch_stick_x = sx;
    g_android_touch_stick_y = sy;
    g_android_touch_cstick_x = cx;
    g_android_touch_cstick_y = cy;
}
