package com.example.smswebhookforwarder

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class DeliveryPulseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        DeliveryWidgetUpdater.updateProvider(
            context,
            DeliveryPulseWidgetProvider::class.java,
            DeliveryWidgetUpdater.WidgetStyle.PULSE
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
            DeliveryPulseWidgetProvider::class.java,
            DeliveryWidgetUpdater.WidgetStyle.PULSE
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (DeliveryWidgetUpdater.handleAction(context, intent)) {
            return
        }
        super.onReceive(context, intent)
    }
}
