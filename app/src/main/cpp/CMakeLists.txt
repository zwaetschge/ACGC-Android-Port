cmake_minimum_required(VERSION 3.22.1)
project(ac_android C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_EXTENSIONS ON)
set(CMAKE_CXX_EXTENSIONS ON)

# =============================================================================
# Paths
#   <project>/app/src/main/cpp  -> this file
#   <project>/                  -> decomp + PC port sources (github clone root)
# =============================================================================
set(DECOMP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")

# =============================================================================
# SDL2 (static; source fetched by the fetchThirdParty Gradle task)
# =============================================================================
set(SDL_SHARED OFF CACHE BOOL "" FORCE)
set(SDL_STATIC ON CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
add_subdirectory(third_party/SDL2)

# HD texture pack converter (BC7 -> ASTC) used by the launcher. Added before
# the decomp-specific compile options below so it builds with clean flags.
add_subdirectory(texconv)

# =============================================================================
# Compiler definitions (mirrors pc/CMakeLists.txt, minus PC_ENHANCEMENTS)
# =============================================================================
add_compile_definitions(
    TARGET_PC
    TARGET_ANDROID
    VERSION=0
    F3DEX_GBI_2
    NDEBUG
    BUGFIXES
    _LANGUAGE_C
    KEYBOARD_TYPING
)

# Same flags as upstream: neutralize the decomp UB classes.
add_compile_options(
    -O2
    -fno-strict-aliasing
    -fwrapv
)

# The decomp calls bcopy/bzero/bcmp (and a few libc functions) without
# declarations — upstream built with gcc's implicit-declaration tolerance,
# clang 16+ errors. Force bionic-compatible declarations into every TU and
# keep any remaining implicit declaration non-fatal (matches upstream gcc).
# The remaining flags downgrade clang default-errors that gcc -fpermissive
# accepted upstream: register in C++17, -1 narrowing to u16 in emu64.c
# initializers, and jaudio's void* -> u32 assignments (fine on 32-bit).
add_compile_options(
    -Wno-error=implicit-function-declaration
    -Wno-error=int-conversion
    -Wno-register
    -Wno-c++11-narrowing
    "$<$<COMPILE_LANGUAGE:C,CXX>:-include${CMAKE_CURRENT_SOURCE_DIR}/android/android_ansi_decls.h>"
)
if(CMAKE_SIZEOF_VOID_P EQUAL 8)
    # 64-bit: the decomp still converts between pointers and 32-bit integers in
    # places the PC port tolerates (pointer recovery, see pc_gbi_ptr.h).
    add_compile_options(
        "$<$<COMPILE_LANGUAGE:C>:-Wno-error=incompatible-function-pointer-types>"
        "$<$<COMPILE_LANGUAGE:C>:-Wno-error=int-to-pointer-cast>"
    )
endif()

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/compat        # glad/gl.h -> GLES3 shim (first!)
    ${CMAKE_CURRENT_SOURCE_DIR}/android
    ${DECOMP_ROOT}/include
    ${DECOMP_ROOT}/src
    ${DECOMP_ROOT}
    ${DECOMP_ROOT}/pc/include
    ${DECOMP_ROOT}/pc/lib/fixnes
)

# =============================================================================
# Collect game source files (same rules as pc/CMakeLists.txt)
# =============================================================================
file(GLOB_RECURSE GAME_C_SOURCES CONFIGURE_DEPENDS
    "${DECOMP_ROOT}/src/*.c"
)
file(GLOB_RECURSE GAME_CXX_SOURCES CONFIGURE_DEPENDS
    "${DECOMP_ROOT}/src/*.cpp"
)

list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/AUS/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/gx/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/os/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/vi/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/pad/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/card/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/dvd/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/ai/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/ar/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/dsp/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/exi/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/si/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/db/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/mtx/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/base/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/gba/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/amcstubs/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/OdemuExi2/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/dolphin/odenotstub/.*")

list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/TRK_MINNOW_DOLPHIN/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/Runtime\\.PPCEABI\\.H/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/MSL_C\\.PPCEABI\\.bare\\.H/.*")

file(GLOB_RECURSE DATA_C_SOURCES CONFIGURE_DEPENDS "${DECOMP_ROOT}/src/data/*.c")
list(APPEND GAME_C_SOURCES ${DATA_C_SOURCES})

list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/src/furniture/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/src/data/model/obj_e_boat/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/ac_npc_shop_common\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/ac_animal_logo_misc\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/m_item_debug\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/actor/npc/.*_anime\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/Famicom/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/MSL_C/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/jaudio_NES/internal/dsp_cardunlock\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/jaudio_NES/internal/dsp_GBAKey\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/libc64/malloc\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/libc64/sprintf\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/libc64/aprintf\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/libultra/.*")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/ReconfigBATs\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/executor\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/ef_effect_lib\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/m_card\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/osreport\\.c$")
list(FILTER GAME_C_SOURCES EXCLUDE REGEX ".*/emu64_utility\\.c$")

list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/AUS/.*")
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/dolphin/.*")
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/TRK_MINNOW_DOLPHIN/.*")
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/Runtime\\.PPCEABI\\.H/.*")
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/MSL_C\\.PPCEABI\\.bare\\.H/.*")
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/Famicom/.*")

list(APPEND GAME_CXX_SOURCES
    ${DECOMP_ROOT}/src/static/Famicom/famicom.cpp
    ${DECOMP_ROOT}/src/static/Famicom/famicom_nesinfo.cpp
    ${DECOMP_ROOT}/src/static/Famicom/ks_nes_core.cpp
    ${DECOMP_ROOT}/src/static/Famicom/ks_nes_draw.cpp
)
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/emu64_print\\.cpp$")
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/jsyswrapper_ext\\.cpp$")
list(FILTER GAME_CXX_SOURCES EXCLUDE REGEX ".*/jsyswrapper_main\\.cpp$")

# =============================================================================
# PC port source files
# =============================================================================
set(PC_SOURCES
    ${DECOMP_ROOT}/pc/src/pc_main.c
    ${DECOMP_ROOT}/pc/src/pc_gx.c
    ${DECOMP_ROOT}/pc/src/pc_gx_tev.c
    ${DECOMP_ROOT}/pc/src/pc_gx_texture.c
    ${DECOMP_ROOT}/pc/src/pc_gbi_runtime.c
    ${DECOMP_ROOT}/pc/src/pc_os.c
    ${DECOMP_ROOT}/pc/src/pc_dvd.c
    ${DECOMP_ROOT}/pc/src/pc_pad.c
    ${DECOMP_ROOT}/pc/src/pc_typing.c
    ${DECOMP_ROOT}/src/static/dolphin/pad/Padclamp.c
    ${DECOMP_ROOT}/pc/src/pc_card.c
    ${DECOMP_ROOT}/pc/src/pc_vi.c
    ${DECOMP_ROOT}/pc/src/pc_audio.c
    ${DECOMP_ROOT}/pc/src/pc_aram.c
    ${DECOMP_ROOT}/pc/src/pc_misc.c
    ${DECOMP_ROOT}/pc/src/pc_mtx.c
    ${DECOMP_ROOT}/pc/src/pc_m_card.c
    ${DECOMP_ROOT}/pc/src/pc_save_bswap.c
    ${DECOMP_ROOT}/pc/src/pc_model_viewer.c
    ${DECOMP_ROOT}/pc/src/pc_texture_pack.c
    ${DECOMP_ROOT}/pc/src/pc_settings.c
    ${DECOMP_ROOT}/pc/src/pc_profiler.c
    ${DECOMP_ROOT}/pc/src/pc_pause_menu.c
    ${DECOMP_ROOT}/pc/src/pc_settings_menu.c
    ${DECOMP_ROOT}/pc/src/pc_menu_util.c
    ${DECOMP_ROOT}/pc/src/pc_text_draw.c
    ${DECOMP_ROOT}/pc/src/pc_keybindings.c
    ${DECOMP_ROOT}/pc/src/pc_nes_fixnes.c
    ${DECOMP_ROOT}/pc/src/pc_stubs.c
    ${DECOMP_ROOT}/pc/src/pc_stubs_cpp.cpp
    ${DECOMP_ROOT}/pc/src/pc_assets.c
    ${DECOMP_ROOT}/pc/src/pc_msg_eur.c
    ${DECOMP_ROOT}/pc/src/pc_disc.c
    ${CMAKE_CURRENT_SOURCE_DIR}/android/android_glue.c
)

# =============================================================================
# fixNES (vendored NES emulator for the in-game NES)
# =============================================================================
set(FIXNES_DIR ${DECOMP_ROOT}/pc/lib/fixnes)
file(GLOB FIXNES_MAPPER_SOURCES ${FIXNES_DIR}/mapper/*.c)
set(FIXNES_SOURCES
    ${FIXNES_DIR}/cpu.c
    ${FIXNES_DIR}/ppu.c
    ${FIXNES_DIR}/apu.c
    ${FIXNES_DIR}/mem.c
    ${FIXNES_DIR}/input.c
    ${FIXNES_DIR}/mapper.c
    ${FIXNES_DIR}/mapperList.c
    ${FIXNES_DIR}/vrc_irq.c
    ${FIXNES_DIR}/audio.c
    ${FIXNES_DIR}/fm2play.c
    ${FIXNES_DIR}/audio_fds.c
    ${FIXNES_DIR}/audio_mmc5.c
    ${FIXNES_DIR}/audio_vrc6.c
    ${FIXNES_DIR}/audio_vrc7.c
    ${FIXNES_DIR}/audio_n163.c
    ${FIXNES_DIR}/audio_s5b.c
    ${FIXNES_MAPPER_SOURCES}
)
set_source_files_properties(${FIXNES_SOURCES}
    PROPERTIES
    COMPILE_OPTIONS "-w;-O2"
    COMPILE_DEFINITIONS "COL_TEX_BSWAP;COL_GL_BSWAP;DO_INLINE_ATTRIBS=1"
)
set_source_files_properties(${DECOMP_ROOT}/pc/src/pc_nes_fixnes.c
    PROPERTIES
    COMPILE_OPTIONS "-w;-O2"
)

# main() renames:
#  - game main()      -> ac_entry
#  - boot.c main()    -> boot_main
#  - pc_main.c main() -> SDL_main (invoked by SDLActivity's nativeRunMain)
set_source_files_properties(
    "${DECOMP_ROOT}/src/main.c"
    PROPERTIES COMPILE_DEFINITIONS "main=ac_entry"
)
set_source_files_properties(
    "${DECOMP_ROOT}/src/static/boot.c"
    PROPERTIES COMPILE_DEFINITIONS "main=boot_main"
)
set_source_files_properties(
    "${DECOMP_ROOT}/pc/src/pc_main.c"
    PROPERTIES COMPILE_DEFINITIONS "main=SDL_main"
)

# .c files that contain C++ code
set_source_files_properties(
    "${DECOMP_ROOT}/src/static/libforest/emu64/emu64.c"
    "${DECOMP_ROOT}/src/static/jaudio_NES/internal/ja_calc.c"
    "${DECOMP_ROOT}/src/static/jaudio_NES/internal/jammain_2.c"
    "${DECOMP_ROOT}/src/static/jaudio_NES/game/game64.c"
    PROPERTIES LANGUAGE CXX
)

# jaudio_NES C files use nullptr
add_compile_options($<$<COMPILE_LANGUAGE:C>:-Dnullptr=NULL>)

# decomp sources: silence warnings (unchanged from upstream)
set(DECOMP_WARN_FLAGS "-w;-fpermissive")
set_source_files_properties(${GAME_C_SOURCES} ${GAME_CXX_SOURCES}
    PROPERTIES COMPILE_OPTIONS "${DECOMP_WARN_FLAGS}"
)
set(PC_C_SOURCES ${PC_SOURCES})
list(FILTER PC_C_SOURCES EXCLUDE REGEX "\\.cpp$")
set(PC_CXX_SOURCES ${PC_SOURCES})
list(FILTER PC_CXX_SOURCES INCLUDE REGEX "\\.cpp$")
set(PC_WARN_SUPPRESS "-Wno-unused-parameter;-Wno-sign-compare;-Wno-unused-function")
set_source_files_properties(${PC_C_SOURCES}
    PROPERTIES COMPILE_OPTIONS "-w"
)
set_source_files_properties(${PC_CXX_SOURCES}
    PROPERTIES COMPILE_OPTIONS "-w;-fpermissive"
)

# =============================================================================
# The game library (SDLActivity loads libAnimalCrossing.so and runs SDL_main)
# =============================================================================
add_library(AnimalCrossing SHARED
    ${PC_SOURCES}
    ${FIXNES_SOURCES}
    ${GAME_C_SOURCES}
    ${GAME_CXX_SOURCES}
)

target_link_libraries(AnimalCrossing
    SDL2::SDL2-static
    GLESv3
    EGL
    log
    android
    m
    dl
)
