package dev.deepseek.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import org.json.JSONObject

/** Runs packaged JavaScript against real Android services and the live DeepSeek API. */
@Suppress("DEPRECATION")
class MobileIntegrationTest : TestCase() {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private fun js(source: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            AgentService.current!!.webView.evaluateJavascript(source) {
                result = it
                latch.countDown()
            }
        }
        check(latch.await(15, TimeUnit.SECONDS)) { "WebView evaluation timed out" }
        return result
    }

    private fun awaitReady() {
        // Instrumentation replaces the target process; reconnect the test emulator's service.
        val automation =
            instrumentation.getUiAutomation(
                android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
            )
        if (PhoneAccessibility.current == null) {
            fun shell(command: String) {
                automation.executeShellCommand(command).use {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                }
            }
            shell("settings put secure enabled_accessibility_services ''")
            shell(
                "settings put secure enabled_accessibility_services dev.deepseek.mobile/dev.deepseek.mobile.PhoneAccessibility",
            )
            shell("settings put secure accessibility_enabled 1")
        }
        val intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)
        val until = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < until) {
            if (
                AgentService.current != null &&
                    PhoneAccessibility.current != null &&
                    js("window.appReady === true") == "true"
            )
                return
            if (
                AgentService.current != null &&
                    js("!!document.getElementById('toast')?.textContent") == "true"
            )
                break
            Thread.sleep(300)
        }
        error(
            "Harness did not boot: " +
                js(
                    "JSON.stringify({url:location.href,ready:document.readyState,harness:typeof MobileHarness,toast:document.getElementById('toast')?.textContent})",
                ),
        )
    }

    private fun async(source: String, timeoutSeconds: Int = 45): JSONObject {
        js(
            "window.__qa=null; (async()=>{$source})().then(value=>window.__qa={ok:true,value},error=>window.__qa={ok:false,error:String(error.message||error)}); true",
        )
        val until = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < until) {
            val raw = js("window.__qa")
            if (raw != "null") {
                val result = JSONObject(raw)
                check(result.optBoolean("ok")) { result.optString("error") }
                return result
            }
            Thread.sleep(350)
        }
        error("Timed out waiting for packaged JavaScript")
    }

    private fun screen(name: String) {
        instrumentation.runOnMainSync {
            val view = MainActivity.current!!.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(context.filesDir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    fun test01NativeControlAndKeyStorage() {
        awaitReady()
        Thread.sleep(1200)
        screen("compose-welcome")
        val keyFile = File(context.filesDir, "test-key")
        check(keyFile.isFile) { "Temporary API key missing" }
        val key = keyFile.readText().trim()
        KeyStore(context).save(key)
        keyFile.delete()
        assertEquals(key, KeyStore(context).read())
        assertFalse(
            context
                .getSharedPreferences("credentials", 0)
                .getString("encrypted", "")!!
                .contains(key),
        )
        val evidence =
            async(
                """
                const call=async(method,args)=>{try{return await rpc(method,args)}catch(e){throw Error(method+': '+e.message)}};
                await call('begin');
                try {
                  await call('launch_app',{package:'dev.deepseek.mobile.lab'});
                  let s=await call('observe');
                  if (!s.image) throw Error('Native screenshot unavailable: '+s.imageError);
                  const original=s;
                  const input=s.nodes.find(n=>n.editable&&!n.password);
                  if (!input) throw Error('Editable node missing');
                  const secret=s.nodes.find(n=>n.password);
                  if (!secret || secret.text!=='[已隐藏]') throw Error('Password not redacted');
                  let passwordBlocked=false;
                  try { await rpc('input_text',{snapshot:s.snapshot,node:secret.id,text:'forbidden'}); } catch(e) { if(!String(e.message).includes('密码框')) throw e; passwordBlocked=true; }
                  if (!passwordBlocked) throw Error('Password input was allowed');
                  await call('input_text',{snapshot:s.snapshot,node:input.id,text:'native-ok'});
                  let staleBlocked=false;
                  try { await rpc('tap',{snapshot:s.snapshot,node:input.id}); } catch(e) { staleBlocked=true; }
                  if (!staleBlocked) throw Error('Stale snapshot was allowed');
                  await call('wait',{milliseconds:500});
                  s=await call('observe');
                  const button=s.nodes.find(n=>n.text==='执行测试');
                  await call('tap',{snapshot:s.snapshot,node:button.id});
                  await rpc('wait',{milliseconds:500});
                  s=await rpc('observe');
                  if (!s.nodes.some(n=>n.text==='结果：native-ok')) throw Error('Click/input not reflected in screen');
                  const counter=s.nodes.find(n=>n.text==='点击测试 · 0 次');
                  if(!counter) throw Error('Counter button missing');
                  await call('tap',{snapshot:s.snapshot,x:(counter.bounds[0]+counter.bounds[2])/2,y:(counter.bounds[1]+counter.bounds[3])/2});
                  await call('wait',{milliseconds:350});
                  s=await call('observe');
                  if(!s.nodes.some(n=>n.text==='点击测试 · 1 次')) throw Error('Coordinate tap failed');
                  const hold=s.nodes.find(n=>n.text==='长按这里');
                  if(!hold) throw Error('Long press button missing');
                  await call('long_press',{snapshot:s.snapshot,x:(hold.bounds[0]+hold.bounds[2])/2,y:(hold.bounds[1]+hold.bounds[3])/2});
                  await call('wait',{milliseconds:350});
                  s=await call('observe');
                  if(!s.nodes.some(n=>n.text==='长按成功：1 次')) throw Error('Long press failed');
                  const scrollBefore=s.nodes.filter(n=>/^滚动项目/.test(n.text)).map(n=>n.text);
                  await call('swipe',{snapshot:s.snapshot,x1:350,y1:1200,x2:350,y2:400,duration:300});
                  await rpc('wait',{milliseconds:500});
                  s=await rpc('observe');
                  const scrollAfter=s.nodes.filter(n=>/^滚动项目/.test(n.text)).map(n=>n.text);
                  const scrolled=JSON.stringify(scrollBefore)!==JSON.stringify(scrollAfter);
                  await rpc('global_action',{action:'home'});
                  return {image:original.image,screenshot:true,passwordBlocked,staleBlocked,inputAndClick:true,coordinateTap:true,longPress:true,scrolled,scrollBefore,scrollAfter};
                } finally { await rpc('end'); }
                """
                    .trimIndent(),
            )
        File(context.filesDir, "native-evidence.json").writeText(evidence.toString(2))
        assertTrue(evidence.getJSONObject("value").getBoolean("scrolled"))
    }

    fun test02LiveDeepSeekAgent() {
        awaitReady()
        instrumentation
            .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("am force-stop dev.deepseek.mobile.lab")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        async("await rpc('test_connection');return true", 120)
        var id = ""
        instrumentation.runOnMainSync {
            AssistantState.queuePaused = false
            AssistantState.addSession().apply {
                access = "full"
                phoneTools = true
            }
            AgentService.current!!.send(
                "打开应用 dev.deepseek.mobile.lab，在测试输入框填入 harness-ok，点击执行测试，再次查看界面，确认出现结果：harness-ok。不要操作其他应用。完成后简洁回答。",
            )
            id = AssistantState.tasks.last().id
        }
        val until = System.currentTimeMillis() + 240000
        while (System.currentTimeMillis() < until) {
            var finished = false
            instrumentation.runOnMainSync {
                finished =
                    AssistantState.tasks.first { it.id == id }.status in
                        listOf("done", "failed", "cancelled")
            }
            if (finished) break
            Thread.sleep(400)
        }
        instrumentation.runOnMainSync {
            val task = AssistantState.tasks.first { it.id == id }
            assertEquals("done", task.status)
            assertTrue(task.steps.any { it.startsWith("输入文字") })
            assertTrue(task.steps.any { it.startsWith("点击") })
            File(context.filesDir, "live-evidence.json").writeText(task.json().toString(2))
        }
        async(
                "await rpc('begin');try{const s=await rpc('observe');return {verified:s.nodes.some(n=>n.text==='结果：harness-ok')}}finally{await rpc('end')}",
            )
            .getJSONObject("value")
            .let { assertTrue(it.getBoolean("verified")) }
        instrumentation.runOnMainSync {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }
        Thread.sleep(1000)
        screen("v03-chat")
    }

    fun test03StopCancelsWaitingTools() {
        awaitReady()
        val evidence =
            async(
                """
                await rpc('begin');
                const waiting=rpc('ask_user',{question:'This is an automated cancellation test'}).then(()=>false,()=>true);
                await new Promise(resolve=>setTimeout(resolve,300));
                await rpc('stop');
                const cancelled=await waiting;
                let blocked=false;
                try { await rpc('global_action',{action:'home'}); } catch(e) {blocked=true;}
                return {cancelled,blocked};
                """
                    .trimIndent(),
            )
        assertTrue(evidence.getJSONObject("value").getBoolean("cancelled"))
        assertTrue(evidence.getJSONObject("value").getBoolean("blocked"))
        File(context.filesDir, "stop-evidence.json").writeText(evidence.toString(2))
    }
}
