/* Android UI layout uses the original 320x240 units plus widescreen space. */
#ifndef PC_UI_H
#define PC_UI_H
extern int g_pc_window_w;
extern int g_pc_window_h;
static inline float pc_ui_width_ratio(void) {
    if (g_pc_window_h <= 0) return 1.0f;
    float ratio = (float)g_pc_window_w * 3.0f / ((float)g_pc_window_h * 4.0f);
    return ratio > 1.0f ? ratio : 1.0f;
}
#endif
