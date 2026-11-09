package dev.deepseek.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class WhaleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            for (id in manager.getAppWidgetIds(ComponentName(context, WhaleWidget::class.java))) {
                val views = RemoteViews(context.packageName, R.layout.whale_widget)
                views.setTextViewText(
                    R.id.widget_status,
                    if (AgentService.current == null) "点击打开助手"
                    else AssistantState.running?.progress ?: "今天也一起慢慢来",
                )
                views.setTextViewText(R.id.widget_balance, "余额 ${AssistantState.balance}")
                val open =
                    PendingIntent.getActivity(
                        context,
                        100,
                        Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                views.setOnClickPendingIntent(R.id.widget_open, open)
                views.setOnClickPendingIntent(
                    R.id.widget_stop,
                    PendingIntent.getForegroundService(
                        context,
                        101,
                        Intent(context, AgentService::class.java).setAction("STOP"),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                manager.updateAppWidget(id, views)
            }
        }
    }
}
