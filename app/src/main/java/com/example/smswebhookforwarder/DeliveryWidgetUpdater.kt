package com.example.smswebhookforwarder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

object DeliveryWidgetUpdater {
    const val ACTION_RETRY_PENDING =
        "com.example.smswebhookforwarder.action.RETRY_PENDING"

    private const val DASHBOARD_MIN_WIDTH_DP = 190
    private const val DASHBOARD_MIN_HEIGHT_DP = 150

    fun updateAll(context: Context) {
        updateProvider(
            context = context,
            providerClass = DeliveryStatsWidgetProvider::class.java,
            style = WidgetStyle.CLASSIC
        )
        updateProvider(
            context = context,
            providerClass = DeliveryPulseWidgetProvider::class.java,
            style = WidgetStyle.PULSE
        )
    }

    fun handleAction(context: Context, intent: Intent): Boolean {
        if (intent.action != ACTION_RETRY_PENDING) {
            return false
        }

        DeliveryStatusStore(context).saveStatus("Manual retry requested from the home widget.")
        SmsWebhookWorker.enqueuePendingDrain(context, replaceExisting = true)
        updateAll(context)
        return true
    }

    fun updateProvider(
        context: Context,
        providerClass: Class<*>,
        style: WidgetStyle
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, providerClass)
        val widgetIds = manager.getAppWidgetIds(componentName)

        widgetIds.forEach { widgetId ->
            updateWidget(context, manager, widgetId, style)
        }
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        style: WidgetStyle
    ) {
        val options = manager.getAppWidgetOptions(widgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val dashboard = minWidth >= DASHBOARD_MIN_WIDTH_DP || minHeight >= DASHBOARD_MIN_HEIGHT_DP

        val layoutId = when (style) {
            WidgetStyle.CLASSIC -> if (dashboard) {
                R.layout.widget_classic_dashboard
            } else {
                R.layout.widget_classic_compact
            }

            WidgetStyle.PULSE -> if (dashboard) {
                R.layout.widget_pulse_dashboard
            } else {
                R.layout.widget_pulse_compact
            }
        }

        val deliveryStatusStore = DeliveryStatusStore(context)
        val outboxStore = SmsOutboxStore(context)
        val stats = deliveryStatusStore.getStats()
        val pendingCount = outboxStore.count()
        val views = RemoteViews(context.packageName, layoutId)

        views.setTextViewText(
            R.id.widgetPendingBadge,
            context.getString(R.string.widget_pending_badge, pendingCount)
        )
        views.setTextViewText(R.id.widgetSuccessCount, stats.successCount.toString())
        views.setTextViewText(R.id.widgetErrorCount, stats.errorCount.toString())
        views.setTextViewText(R.id.widgetPendingCount, pendingCount.toString())

        if (dashboard) {
            views.setTextViewText(R.id.widgetLastStatus, deliveryStatusStore.getLastStatus())
            views.setOnClickPendingIntent(R.id.widgetRetryButton, retryPendingIntent(context))
        }

        views.setOnClickPendingIntent(R.id.widgetRoot, openAppPendingIntent(context))
        manager.updateAppWidget(widgetId, views)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun retryPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DeliveryStatsWidgetProvider::class.java).apply {
            action = ACTION_RETRY_PENDING
        }
        return PendingIntent.getBroadcast(
            context,
            2002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    enum class WidgetStyle {
        CLASSIC,
        PULSE
    }
}
