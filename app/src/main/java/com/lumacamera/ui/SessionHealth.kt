package com.lumacamera.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.lumacamera.core.SessionHealthPolicy
import java.util.Locale

/** One explicit snapshot. No receiver registration, frame polling, inference or quality changes. */
object SessionHealth {
    data class Snapshot(
        val batteryPercent: Int?, val charging: Boolean?, val batteryTemperatureC: Float?, val thermalStatus: Int?
    ) {
        fun description(): String = buildString {
            append("Bateria: ").append(batteryPercent?.let { "$it%" } ?: "indisponível")
            if (charging == true) append(" · carregando")
            batteryTemperatureC?.let {
                append("\nTemperatura da bateria: ").append(String.format(Locale.getDefault(), "%.1f °C", it))
            }
            append("\nAndroid: ").append(SessionHealthPolicy.thermalLabel(thermalStatus))
            SessionHealthPolicy.guidance(thermalStatus)?.let { append("\n").append(it) }
            append("\nLeitura pontual. O alerta térmico é informado pelo Android; não mede a temperatura da câmera.")
        }
    }

    fun read(context: Context): Snapshot {
        val battery = runCatching {
            context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temperature = battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val thermal = runCatching { context.getSystemService(PowerManager::class.java)?.currentThermalStatus }.getOrNull()
        return Snapshot(SessionHealthPolicy.batteryPercent(level, scale),
            status?.takeIf { it >= 0 }?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL },
            SessionHealthPolicy.batteryTemperature(temperature), SessionHealthPolicy.thermalStatus(thermal))
    }
}
