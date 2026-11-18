package com.follow.clash

import android.content.Context
import com.follow.clash.plugins.AppPlugin
import com.follow.clash.plugins.BatteryPlugin
import com.follow.clash.plugins.ServicePlugin
import com.follow.clash.plugins.TilePlugin
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor

/** One engine owns the original FlClash providers, whether its Activity is visible or not. */
object EmbeddedEngine {
    var configure: ((FlutterEngine) -> Unit)? = null
    @Volatile var checkAgentRequest: ((String) -> Unit)? = null
    private var instance: FlutterEngine? = null

    fun authorize(requestId: String) {
        checkNotNull(checkAgentRequest) { "Agent bridge is unavailable" }.invoke(requestId)
    }

    fun get(context: Context): FlutterEngine {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        instance?.let {
            return it
        }
        return FlutterEngine(context.applicationContext).also { engine ->
            engine.plugins.add(AppPlugin())
            engine.plugins.add(BatteryPlugin())
            engine.plugins.add(ServicePlugin())
            engine.plugins.add(TilePlugin())
            ServiceState.attachFlutterEngine(engine)
            configure?.invoke(engine)
            instance = engine
            engine.dartExecutor.executeDartEntrypoint(DartExecutor.DartEntrypoint.createDefault())
        }
    }
}
