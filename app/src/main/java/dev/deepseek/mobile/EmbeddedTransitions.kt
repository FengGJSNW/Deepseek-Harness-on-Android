package dev.deepseek.mobile

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle

/** Configure the embedded Activity without changing FlClash's pages or its shared engine. */
internal fun Application.installEmbeddedTransitions() {
    registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT >= 34 && activity is com.follow.clash.MainActivity) {
                    activity.overrideActivityTransition(
                        Activity.OVERRIDE_TRANSITION_OPEN,
                        R.anim.dsha_scene_enter,
                        R.anim.dsha_scene_exit,
                    )
                    activity.overrideActivityTransition(
                        Activity.OVERRIDE_TRANSITION_CLOSE,
                        R.anim.dsha_scene_enter,
                        R.anim.dsha_scene_exit,
                    )
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        },
    )
}
