package dev.deepseek.mobile

import androidx.test.platform.app.InstrumentationRegistry
import dev.deepseek.mobile.runtime.LinuxVm
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeRecoveryTest {
    @Test
    fun restoredGuestReestablishesItsVirtualNetwork() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.filesDir, "network-restore-qa").apply { mkdirs() }
            File(context.filesDir, "toolchains/light-v1/machine.state")
                .copyTo(File(dir, "machine.state"), overwrite = true)
            val vm = LinuxVm(context, dir) { true }
            val evidence = JSONObject()
            try {
                evidence.put(
                    "nativeDns",
                    java.net.InetAddress.getAllByName("dl-cdn.alpinelinux.org")
                        .map { it.hostAddress }
                        .joinToString(),
                )
                val boot = vm.start()
                assertTrue(boot.getBoolean("virtualNetworkReinitialized"))
                evidence.put("boot", boot)
                evidence.put(
                    "before",
                    vm.execute("ip addr; ip route; cat /mnt/alpine/etc/resolv.conf", timeout = 20),
                )
                evidence.put(
                    "firstLookup",
                    vm.execute(
                        "nslookup dl-cdn.alpinelinux.org",
                        root = "/mnt/alpine",
                        timeout = 15,
                    ),
                )
                val after =
                    vm.execute(
                        "curl -I --connect-timeout 15 --max-time 25 https://dl-cdn.alpinelinux.org/alpine/v3.24/main/x86/APKINDEX.tar.gz",
                        root = "/mnt/alpine",
                        timeout = 30,
                    )
                evidence.put("after", after)
                assertEquals(0, after.getInt("exitCode"))
                evidence.put("passed", true)
            } catch (error: Throwable) {
                evidence.put("failure", error.stackTraceToString())
                throw error
            } finally {
                vm.close()
                File(context.filesDir, "v080-network-restore.json").writeText(evidence.toString(2))
                dir.deleteRecursively()
            }
        }
}
