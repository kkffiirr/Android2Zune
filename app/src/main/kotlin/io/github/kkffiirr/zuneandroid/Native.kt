package io.github.kkffiirr.zuneandroid

fun interface LineSink { fun onLine(line: String) }

object Native {
    init { System.loadLibrary("crypto"); System.loadLibrary("zuneandroid") }

    /** Runs the library's full upload sequence on [dir]; returns 0 on success. Blocks. */
    @JvmStatic external fun upload(fd: Int, mtpzPath: String, dir: String, verbose: Boolean, sink: LineSink): Int

    // --- MP3 encoding (LAME) used by Converter ---
    /** Opens an MP3 file for writing; returns a handle, or 0 on failure. vbrQuality: 0 = best (~245 kbps), 2 = ~190 kbps. */
    @JvmStatic external fun mp3Open(inRate: Int, channels: Int, vbrQuality: Int, path: String): Long
    /** [pcm] is interleaved 16-bit; [frames] is samples per channel. Returns 0 on success. */
    @JvmStatic external fun mp3Write(handle: Long, pcm: ShortArray, frames: Int): Int
    @JvmStatic external fun mp3Close(handle: Long): Int
    /** Copies tags (and cover art when present) from [src] into the MP3 [dst]. Returns "" on success, else an error message. */
    @JvmStatic external fun copyTags(src: String, dst: String): String
}
