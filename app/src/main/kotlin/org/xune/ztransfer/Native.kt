package org.xune.ztransfer

fun interface LineSink { fun onLine(line: String) }

object Native {
    init { System.loadLibrary("crypto"); System.loadLibrary("xune_sync") }

    /** Runs the library's full upload sequence on [dir]; returns 0 on success. Blocks. */
    @JvmStatic external fun upload(fd: Int, mtpzPath: String, dir: String, verbose: Boolean, sink: LineSink): Int
}
