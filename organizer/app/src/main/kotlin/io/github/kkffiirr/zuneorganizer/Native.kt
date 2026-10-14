package io.github.kkffiirr.zuneorganizer

fun interface LineSink { fun onLine(line: String) }

object Native {
    init { System.loadLibrary("crypto"); System.loadLibrary("zuneorganizer") }

    /** cmd "list" or "edit". Returns "OK ..." / "ERR ..." on the first line, payload after it. Blocks. */
    @JvmStatic external fun run(fd: Int, mtpzPath: String, cmd: String, arg: String, sink: LineSink): String
}
