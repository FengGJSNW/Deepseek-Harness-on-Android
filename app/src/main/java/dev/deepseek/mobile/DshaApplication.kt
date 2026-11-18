package dev.deepseek.mobile

import android.app.Application
import com.follow.clash.EmbeddedEngine
import com.follow.clash.common.GlobalState
import dev.deepseek.mobile.proxy.FlClashRuntime

class DshaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GlobalState.init(this)
        installEmbeddedTransitions()
        EmbeddedEngine.configure = { FlClashRuntime.get(this).attach(it) }
    }
}
