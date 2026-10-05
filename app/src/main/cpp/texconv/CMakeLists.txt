# libactexconv: decodes Dolphin texture-pack DDS (BC7/BC1/BC3/RGBA) and
# re-encodes it as ASTC 4x4 with a mip chain (most Android GPUs lack BPTC).
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../third_party)
file(GLOB ASTCENC_SOURCES ${TP}/astc-encoder/Source/astcenc_*.cpp)

add_library(actexconv SHARED texconv.cpp ${ASTCENC_SOURCES})
target_include_directories(actexconv PRIVATE ${TP}/astc-encoder/Source ${TP}/bcdec)
# armeabi-v7a: astcenc's NEON path needs AArch64, use the portable backend.
target_compile_definitions(actexconv PRIVATE
    ASTCENC_NEON=0 ASTCENC_SSE=0 ASTCENC_AVX=0 ASTCENC_POPCNT=0 ASTCENC_F16C=0)
target_compile_options(actexconv PRIVATE -O3 -ffp-contract=fast)
target_link_libraries(actexconv log)
