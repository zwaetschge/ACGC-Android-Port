package com.acpc.port;

/** JNI bridge to libactexconv (BC7/BC1/BC3/RGBA DDS -> ASTC 4x4 DDS with mips). */
final class TexConv {
    static {
        System.loadLibrary("actexconv");
    }

    /** astcenc quality preset: fast (10) keeps the one-time conversion in minutes. */
    static final float QUALITY = 10f;

    private TexConv() {
    }

    /** @return number of mip levels written, or a negative error code. */
    static native int convert(byte[] dds, int length, String outPath, float quality);
}
