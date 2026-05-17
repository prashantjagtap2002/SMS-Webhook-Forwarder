package com.example.smswebhookforwarder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class DeliveryStatsWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        DeliveryWidgetUpdater.updateProvider(
            context,
            DeliveryStatsWidgetProvider::class.java,
            DeliveryWidgetUpdater.WidgetStyle.CLASSIC
        )
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        DeliveryWidgetUpdater.updateProvider(
            context,
            DeliveryStatsWidgetProvider::class.java,
            DeliveryWidgetUpdater.WidgetStyle.CLASSIC
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (DeliveryWidgetUpdater.handleAction(context, intent)) {
            return
        }
        super.onReceive(context, intent)
    }
}
