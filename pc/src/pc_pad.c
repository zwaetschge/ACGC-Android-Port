/* pc_pad.c - GC controller input via SDL gamepad + keyboard */
#include "pc_platform.h"
#include "pc_typing.h"
#include "pc_keybindings.h"
#include "pc_settings.h"
#include <dolphin/pad.h>

/* analog stick constants */
#define STICK_MAGNITUDE     80
#define RUMBLE_DURATION_MS  200

static SDL_GameController* g_controller = NULL;

/* deadzone percent (0-40) -> raw SDL axis threshold */
static int deadzone_threshold(int percent) {
    if (percent < 0)  percent = 0;
    if (percent > 90) percent = 90;
    return percent * 32767 / 100;
}

/* is a remappable pad binding currently held? */
static int pad_code_pressed(PCPadCode code) {
    if (code < 0) return 0;
    if (code & PC_PAD_AXIS_BIT) {
        return SDL_GameControllerGetAxis(g_controller,
            (SDL_GameControllerAxis)(code & 0xFF)) > PC_PAD_AXIS_PRESS;
    }
    return SDL_GameControllerGetButton(g_controller, (SDL_GameControllerButton)code);
}

/* analog trigger value for the L/R binding (digital bindings read as full press) */
static u8 pad_trigger_value(PCPadCode code) {
    if (code < 0) return 0;
    if (code & PC_PAD_AXIS_BIT) {
        s16 v = SDL_GameControllerGetAxis(g_controller, (SDL_GameControllerAxis)(code & 0xFF));
        if (v < 0) v = 0;
        return (u8)(v >> 7);
    }
    return SDL_GameControllerGetButton(g_controller, (SDL_GameControllerButton)code) ? 255 : 0;
}

BOOL PADInit(void) {
    for (int i = 0; i < SDL_NumJoysticks(); i++) {
        if (SDL_IsGameController(i)) {
            g_controller = SDL_GameControllerOpen(i);
            if (g_controller) {
                break;
            }
        }
    }
    return TRUE;
}

u32 PADRead(PADStatus* status) {
    memset(status, 0, sizeof(PADStatus) * 4);

    const u8* keys = SDL_GetKeyboardState(NULL);
    u32 mouse = SDL_GetMouseState(NULL, NULL);
    u16 buttons = 0;
    s8 stickX = 0, stickY = 0;
    s8 cstickX = 0, cstickY = 0;

    /* Suppress keyboard-to-button mapping when typing into the in-game text editor */
    if (!(g_pc_typing_mode && g_pc_editor_active)) {
        /* helper: check if a PCInputCode is currently pressed */
        #define INPUT_PRESSED(code) \
            (((code) & PC_INPUT_MOUSE_BIT) \
                ? (mouse & SDL_BUTTON((code) & 0xFF)) \
                : keys[(SDL_Scancode)(code)])

        /* buttons (from keybindings.ini) */
        PCKeybindings* kb = &g_pc_keybindings;
        if (INPUT_PRESSED(kb->a))     buttons |= PAD_BUTTON_A;
        if (INPUT_PRESSED(kb->b))     buttons |= PAD_BUTTON_B;
        if (INPUT_PRESSED(kb->x))     buttons |= PAD_BUTTON_X;
        if (INPUT_PRESSED(kb->y))     buttons |= PAD_BUTTON_Y;
        if (INPUT_PRESSED(kb->start)) buttons |= PAD_BUTTON_START;
        if (INPUT_PRESSED(kb->z))     buttons |= PAD_TRIGGER_Z;
        if (INPUT_PRESSED(kb->l))     buttons |= PAD_TRIGGER_L;
        if (INPUT_PRESSED(kb->r))     buttons |= PAD_TRIGGER_R;

        /* main stick */
        if (INPUT_PRESSED(kb->stick_up))    stickY += STICK_MAGNITUDE;
        if (INPUT_PRESSED(kb->stick_down))  stickY -= STICK_MAGNITUDE;
        if (INPUT_PRESSED(kb->stick_left))  stickX -= STICK_MAGNITUDE;
        if (INPUT_PRESSED(kb->stick_right)) stickX += STICK_MAGNITUDE;

        /* C-stick */
        if (INPUT_PRESSED(kb->cstick_up))    cstickY += STICK_MAGNITUDE;
        if (INPUT_PRESSED(kb->cstick_down))  cstickY -= STICK_MAGNITUDE;
        if (INPUT_PRESSED(kb->cstick_left))  cstickX -= STICK_MAGNITUDE;
        if (INPUT_PRESSED(kb->cstick_right)) cstickX += STICK_MAGNITUDE;

        /* D-pad */
        if (INPUT_PRESSED(kb->dpad_up))    buttons |= PAD_BUTTON_UP;
        if (INPUT_PRESSED(kb->dpad_down))  buttons |= PAD_BUTTON_DOWN;
        if (INPUT_PRESSED(kb->dpad_left))  buttons |= PAD_BUTTON_LEFT;
        if (INPUT_PRESSED(kb->dpad_right)) buttons |= PAD_BUTTON_RIGHT;

        #undef INPUT_PRESSED
    }

    /* hotplug */
    if (!g_controller) {
        for (int i = 0; i < SDL_NumJoysticks(); i++) {
            if (SDL_IsGameController(i)) {
                g_controller = SDL_GameControllerOpen(i);
                if (g_controller) break;
            }
        }
    }

    if (g_controller) {
        if (!SDL_GameControllerGetAttached(g_controller)) {
            SDL_GameControllerClose(g_controller);
            g_controller = NULL;
        }
    }
    if (g_controller) {
        PCPadBindings* pb = &g_pc_padbindings;
        if (pad_code_pressed(pb->a))     buttons |= PAD_BUTTON_A;
        if (pad_code_pressed(pb->b))     buttons |= PAD_BUTTON_B;
        if (pad_code_pressed(pb->x))     buttons |= PAD_BUTTON_X;
        if (pad_code_pressed(pb->y))     buttons |= PAD_BUTTON_Y;
        if (pad_code_pressed(pb->start)) buttons |= PAD_BUTTON_START;
        if (pad_code_pressed(pb->z))     buttons |= PAD_TRIGGER_Z;
        if (pad_code_pressed(pb->l))     buttons |= PAD_TRIGGER_L;
        if (pad_code_pressed(pb->r))     buttons |= PAD_TRIGGER_R;
        if (pad_code_pressed(pb->dpad_up))    buttons |= PAD_BUTTON_UP;
        if (pad_code_pressed(pb->dpad_down))  buttons |= PAD_BUTTON_DOWN;
        if (pad_code_pressed(pb->dpad_left))  buttons |= PAD_BUTTON_LEFT;
        if (pad_code_pressed(pb->dpad_right)) buttons |= PAD_BUTTON_RIGHT;

        int stick_dz  = deadzone_threshold(g_pc_settings.stick_deadzone);
        int cstick_dz = deadzone_threshold(g_pc_settings.cstick_deadzone);

        s16 lx = SDL_GameControllerGetAxis(g_controller, SDL_CONTROLLER_AXIS_LEFTX);
        s16 ly = SDL_GameControllerGetAxis(g_controller, SDL_CONTROLLER_AXIS_LEFTY);
        if (abs(lx) > stick_dz) {
            int sx = lx >> 8;
            if (sx > 127) sx = 127; else if (sx < -128) sx = -128;
            stickX = (s8)sx;
        }
        if (abs(ly) > stick_dz) {
            int sy = -(ly >> 8);
            if (sy > 127) sy = 127; else if (sy < -128) sy = -128;
            stickY = (s8)sy;
        }

        s16 rx = SDL_GameControllerGetAxis(g_controller, SDL_CONTROLLER_AXIS_RIGHTX);
        s16 ry = SDL_GameControllerGetAxis(g_controller, SDL_CONTROLLER_AXIS_RIGHTY);
        if (abs(rx) > cstick_dz) {
            int srx = rx >> 8;
            if (srx > 127) srx = 127; else if (srx < -128) srx = -128;
            cstickX = (s8)srx;
        }
        if (abs(ry) > cstick_dz) {
            int sry = -(ry >> 8);
            if (sry > 127) sry = 127; else if (sry < -128) sry = -128;
            cstickY = (s8)sry;
        }

        status[0].triggerLeft  = pad_trigger_value(pb->l);
        status[0].triggerRight = pad_trigger_value(pb->r);
    }

#ifdef TARGET_ANDROID
    /* On-screen touch controls: GC button bits and stick values from the overlay */
    {
        extern volatile int g_android_touch_buttons;
        extern volatile int g_android_touch_stick_x, g_android_touch_stick_y;
        extern volatile int g_android_touch_cstick_x, g_android_touch_cstick_y;
        buttons |= (u16)g_android_touch_buttons;
        if (g_android_touch_stick_x || g_android_touch_stick_y) {
            stickX = (s8)g_android_touch_stick_x;
            stickY = (s8)g_android_touch_stick_y;
        }
        if (g_android_touch_cstick_x || g_android_touch_cstick_y) {
            cstickX = (s8)g_android_touch_cstick_x;
            cstickY = (s8)g_android_touch_cstick_y;
        }
    }
#endif

    status[0].button = buttons;
    status[0].stickX = stickX;
    status[0].stickY = stickY;
    status[0].substickX = cstickX;
    status[0].substickY = cstickY;
    status[0].err = 0; /* PAD_ERR_NONE */

    /* Android input diagnostic: log every button-state change + SDL focus/space state */
    {
        extern SDL_Window* g_pc_window;
        static u16 s_last_btn = 0xFFFF;
        if (buttons != s_last_btn) {
            printf("[PAD] btn=%04x stick=(%d,%d) focus=%d space=%d bindA=%d\n",
                   buttons, stickX, stickY,
                   g_pc_window ? ((SDL_GetWindowFlags(g_pc_window) & SDL_WINDOW_INPUT_FOCUS) != 0) : -1,
                   (int)keys[SDL_SCANCODE_SPACE], (int)g_pc_keybindings.a);
            s_last_btn = buttons;
        }
    }

    return PAD_CHAN0_BIT; /* Controller 1 connected */
}

void PADControlMotor(s32 chan, u32 command) {
    if (g_controller && chan == 0) {
        u16 intensity = (command == 1) ? 0xFFFF : 0;
        SDL_GameControllerRumble(g_controller, intensity, intensity, RUMBLE_DURATION_MS);
    }
}

void PADControlAllMotors(const u32* commands) {
    PADControlMotor(0, commands[0]);
}

void PADCleanup(void) {
    if (g_controller) {
        SDL_GameControllerClose(g_controller);
        g_controller = NULL;
    }
}

BOOL PADReset(u32 mask) { (void)mask; return TRUE; }
BOOL PADRecalibrate(u32 mask) { (void)mask; return TRUE; }
BOOL PADSync(void) { return TRUE; }
void PADSetSpec(u32 spec) { (void)spec; }
void PADSetAnalogMode(u32 mode) { (void)mode; }
/* PADClamp compiled from decomp: src/static/dolphin/pad/Padclamp.c */
BOOL PADGetType(s32 chan, u32* type) { if (type) *type = 0x09000000; return TRUE; }
