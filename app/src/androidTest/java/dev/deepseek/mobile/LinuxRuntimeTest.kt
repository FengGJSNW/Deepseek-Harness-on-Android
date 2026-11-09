package dev.deepseek.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.runtime.LinuxVm
import dev.deepseek.mobile.runtime.RuntimeArtifacts
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Only unprivileged emulator VMs. No Android Shell/Root requests and no private-device data. */
class LinuxRuntimeTest {
    @Test
    fun guestBootIsolationCommandAndRestore() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val reportDir = File(context.filesDir, "qa-v080").apply { mkdirs() }
            val runtimeDir = File(context.filesDir, "runtime-test-${UUID.randomUUID()}")
            val sentinel =
                File(context.filesDir, "host-only-v080.txt").apply {
                    writeText(UUID.randomUUID().toString())
                }
            val secret = sentinel.readText()
            var vm = LinuxVm(context, runtimeDir)
            val evidence = JSONObject().put("artifacts", RuntimeArtifacts.manifest())
            try {
                evidence.put("boot", vm.start())
                val result =
                    vm.execute(
                        "uname -sr; id; tty; printf '中文-output\\n'; printf 'stderr-probe\\n' >&2; printf kept > /workspace/kept.txt; for t in sh script unshare git ssh python node; do command -v \"\u0024t\" || true; done",
                    )
                evidence.put("command", result)
                assertEquals(0, result.getInt("exitCode"))
                assertTrue(result.getString("stdout").contains("Linux 6.8.12"))
                assertTrue(result.getString("stdout").contains("中文-output"))
                assertTrue(result.getString("stderr").contains("stderr-probe"))
                assertFalse(result.getBoolean("androidRoot"))
                val attack =
                    vm.execute(
                        "cat '${sentinel.absolutePath}'; ln -s '${sentinel.absolutePath}' /workspace/escape; cat /workspace/escape; cat /proc/self/root${sentinel.absolutePath}; ls /data/user/0/dev.deepseek.mobile",
                    )
                evidence.put("hostPaths", attack)
                assertFalse(attack.getString("stdout").contains(secret))
                assertNotEquals(0, attack.getInt("exitCode"))
                assertEquals(secret, sentinel.readText())
                val terminalText = StringBuilder()
                vm.onPty = { _, bytes -> terminalText.append(String(bytes, Charsets.UTF_8)) }
                val pty = vm.openPty(rows = 24, cols = 80)
                vm.input(pty, "tty; printf 'PTY_UTF8_中文\\n'; stty size\n".toByteArray())
                withTimeout(15000) { while (!terminalText.contains("24 80")) delay(50) }
                assertTrue(terminalText.contains("/dev/pts/"))
                vm.resize(pty, 30, 100)
                vm.input(pty, "stty size; sleep 60\n".toByteArray())
                withTimeout(10000) { while (!terminalText.contains("30 100")) delay(50) }
                vm.input(pty, byteArrayOf(3))
                delay(150)
                vm.input(pty, "echo PTY_AFTER_CTRL_C\n".toByteArray())
                withTimeout(10000) {
                    while (!terminalText.contains("\r\nPTY_AFTER_CTRL_C\r\n")) delay(50)
                }
                evidence.put("pty", terminalText.toString())
                vm.closePty(pty)
                val namespace =
                    vm.execute(
                        "mkdir -p /mnt/worktrees/test; printf own > /mnt/worktrees/test/only.txt; true",
                    )
                val tree = vm.execute("pwd; cat /workspace/only.txt", tree = "/mnt/worktrees/test")
                evidence.put("worktreeNamespace", tree)
                assertEquals(0, tree.getInt("exitCode"))
                assertTrue(tree.getString("stdout").contains("own"))
                val full =
                    vm.execute(
                        "set -e; gzip -dc /mnt/alpine.tar.gz | tar -xf - -C /mnt/alpine; mkdir -p /mnt/alpine/proc /mnt/alpine/dev /mnt/alpine/sys /mnt/alpine/workspace; mount -t proc proc /mnt/alpine/proc; mount --rbind /dev /mnt/alpine/dev; mount -t sysfs sysfs /mnt/alpine/sys; printf 'nameserver 192.168.86.1\\n' > /mnt/alpine/etc/resolv.conf; udhcpc -i eth0 -q -n",
                        timeout = 60,
                    )
                evidence.put("alpineSetup", full)
                assertEquals(0, full.getInt("exitCode"))
                val alpine =
                    vm.execute(
                        "cat /etc/alpine-release; apk --version; printf 'Alpine_中文\\n'; wget -q -O /tmp/network.txt http://example.com; head -c 500 /tmp/network.txt",
                        root = "/mnt/alpine",
                        tree = "/mnt/worktrees/test",
                        timeout = 60,
                    )
                evidence.put("alpine", alpine)
                assertEquals(0, alpine.getInt("exitCode"))
                assertTrue(alpine.getString("stdout").contains("3.24.3"))
                assertTrue(alpine.getString("stdout").contains("Example Domain"))
                // Toolchain installation/execution belongs to RuntimeManagerTest and
                // PackageManagerTest. Keep this fresh-boot boundary test independent of a
                // second, redundant download of the entire development toolchain.
                evidence.put("snapshot", vm.snapshot())
                vm.close()
                vm = LinuxVm(context, runtimeDir)
                evidence.put("restore", vm.start())
                val restored = vm.execute("cat /workspace/kept.txt")
                evidence.put("restoredFile", restored)
                assertEquals("kept", restored.getString("stdout"))
                assertEquals(0, restored.getInt("exitCode"))
                assertEquals(secret, sentinel.readText())
                evidence.put("passed", true)
            } catch (error: Throwable) {
                evidence
                    .put("failure", error.stackTraceToString())
                    .put("bootLog", vm.bootLog)
                    .put("runtimeError", vm.failed)
                throw error
            } finally {
                reportDir.resolve("linux-android.json").writeText(evidence.toString(2))
                vm.close()
                sentinel.delete()
                // This unique, test-created directory is inside the app's own private files
                // directory.
                check(
                    runtimeDir.canonicalPath.startsWith(
                        context.filesDir.canonicalPath + File.separator,
                    ),
                )
                runtimeDir.deleteRecursively()
            }
        }
}
