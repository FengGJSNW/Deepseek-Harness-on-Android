package dev.deepseek.mobile.terminal

/** Only the child between fork and exec enters the namespace; the UI/IPC process never does. */
object NativeProcess {
    init {
        val path = System.getProperty("dsha.nativeLibrary")
        if (path == null) System.loadLibrary("terminal_guard") else System.load(path)
    }

    external fun spawn(command: String, cwd: String, guarded: Boolean, workspace: String): IntArray

    /** Fixed app_process entry only; drops Root before loading its JVM or any command. */
    external fun spawnShellWorker(apk: String, library: String): IntArray

    external fun waitFor(pid: Int): Int

    external fun terminate(pid: Int)
}
