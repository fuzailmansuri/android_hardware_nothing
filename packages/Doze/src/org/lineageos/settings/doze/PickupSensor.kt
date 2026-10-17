/*
 * Copyright (C) 2021-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.doze

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.util.concurrent.Executors

class PickupSensor(
    private val context: Context,
    sensorType: String,
    private val sensorValue: Float,
) : SensorEventListener {
    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)

    private val sensorManager = context.getSystemService(SensorManager::class.java)!!
    private val sensor = Utils.getSensor(sensorManager, sensorType)
    private val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    @Volatile
    private var proximityCovered = false
    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            proximityCovered = event.values[0] <= 0f
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private val executorService = Executors.newSingleThreadExecutor()
    private var entryTimestamp = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (DEBUG) Log.d(TAG, "Got sensor event: ${event.values[0]}")
        val delta = SystemClock.elapsedRealtime() - entryTimestamp
        if (delta < MIN_PULSE_INTERVAL_MS) {
            return
        }
        entryTimestamp = SystemClock.elapsedRealtime()
        if (event.values[0] == sensorValue) {
            if (Utils.isPickUpSetToWake(context)) {
                if (!proximityCovered) {
                    wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
                    powerManager.wakeUp(
                        SystemClock.uptimeMillis(),
                        PowerManager.WAKE_REASON_GESTURE,
                        TAG,
                        Display.DEFAULT_DISPLAY,
                    )
                }
            } else {
                Utils.launchDozePulse(context)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    fun enable() {
        if (sensor != null) {
            Log.d(TAG, "Enabling")
            executorService.submit {
                entryTimestamp = SystemClock.elapsedRealtime()
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                proximitySensor?.let {
                    sensorManager.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
        }
    }

    fun disable() {
        if (sensor != null) {
            Log.d(TAG, "Disabling")
            executorService.submit {
                sensorManager.unregisterListener(this, sensor)
                proximitySensor?.let {
                    sensorManager.unregisterListener(proximityListener, it)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PickupSensor"
        private const val DEBUG = false

        private const val MIN_PULSE_INTERVAL_MS = 2500L
        private const val WAKELOCK_TIMEOUT_MS = 300L
    }
}
