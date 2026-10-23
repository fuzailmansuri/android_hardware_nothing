/*
 * Copyright (C) 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing.assistkey

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.VibrationEffect
import android.util.Log
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler

/**
 * Loaded into system_server by PhoneWindowManager and called from interceptKeyBeforeQueueing,
 * i.e. on the input reader thread. handleKeyEvent() only matches the key against cached state
 * and hands it to a dedicated thread; everything else (IPC, sensors, actions) runs there.
 */
class KeyHandler(context: Context) : DeviceKeyHandler {
    private val handler = Handler(HandlerThread(Constants.TAG).apply { start() }.looper)
    private val settings = KeySettings(context, handler)
    private val actions = ActionExecutor(context, handler, settings)

    private val powerManager = context.getSystemService(PowerManager::class.java)!!
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK, "${Constants.TAG}:Press"
    ).apply { setReferenceCounted(false) }

    private enum class Press { IGNORED, NORMAL, SILENCE, SHUTTER }

    // Handler thread state
    private var press = Press.IGNORED
    private var longPressFired = false
    private var pendingSingle: Runnable? = null
    private var inPocket: Boolean? = false // null while the proximity check is running
    private var deferred: (() -> Unit)? = null

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (event.keyCode != Constants.KEYCODE_ESSENTIAL_KEY &&
            event.keyCode != Constants.KEYCODE_ESSENTIAL_KEY_BUTTON1 &&
            event.scanCode != Constants.SCANCODE_ESSENTIAL_KEY
        ) {
            return event
        }
        if (!settings.current.enabled) {
            return event
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) handler.post { onDown() }
            KeyEvent.ACTION_UP -> handler.post { onUp() }
        }
        // Consume so the framework does not launch its own assist action
        return null
    }

    private fun onDown() {
        settings.ensureLoaded()
        actions.prepare()
        val s = settings.current
        val interactive = powerManager.isInteractive
        longPressFired = false

        press = when {
            s.callSilence && actions.isRinging() -> Press.SILENCE
            interactive && s.cameraShutter && actions.isCameraInForeground() -> Press.SHUTTER
            !interactive && !s.screenOff -> Press.IGNORED
            else -> Press.NORMAL
        }

        when (press) {
            Press.SHUTTER -> {
                actions.haptic(VibrationEffect.EFFECT_CLICK)
                actions.injectCameraKey(KeyEvent.ACTION_DOWN)
            }
            Press.NORMAL -> {
                if (!interactive) {
                    // Keep the CPU up until the press resolves
                    wakeLock.acquire(Constants.WAKELOCK_DURATION_MS)
                }
                // The second press of a double press reuses the first press's pocket result
                if (pendingSingle == null) {
                    startPocketCheck(!interactive && s.pocketCheck)
                }
                if (s.longAction != Constants.ACTION_NONE) {
                    handler.postDelayed(longPress, Constants.DEFAULT_LONG_PRESS_TIMEOUT_MS)
                }
            }
            else -> {}
        }
    }

    private fun onUp() {
        when (press) {
            Press.SILENCE -> actions.silenceRinger()
            Press.SHUTTER -> actions.injectCameraKey(KeyEvent.ACTION_UP)
            Press.NORMAL -> onRelease()
            Press.IGNORED -> {}
        }
        press = Press.IGNORED
    }

    private val longPress = Runnable {
        longPressFired = true
        cancelPendingSingle()
        val s = settings.current
        whenNotInPocket {
            actions.run(s.longAction, s.longApp, VibrationEffect.EFFECT_HEAVY_CLICK)
        }
    }

    private fun onRelease() {
        handler.removeCallbacks(longPress)
        if (longPressFired || inPocket == true) {
            return
        }
        val s = settings.current
        when {
            pendingSingle != null -> {
                cancelPendingSingle()
                whenNotInPocket {
                    actions.run(s.doubleAction, s.doubleApp, VibrationEffect.EFFECT_DOUBLE_CLICK)
                }
            }
            s.doubleAction == Constants.ACTION_NONE -> whenNotInPocket {
                actions.run(s.singleAction, s.singleApp, VibrationEffect.EFFECT_CLICK)
            }
            else -> {
                val single = Runnable {
                    pendingSingle = null
                    whenNotInPocket {
                        actions.run(s.singleAction, s.singleApp, VibrationEffect.EFFECT_CLICK)
                    }
                }
                pendingSingle = single
                handler.postDelayed(single, Constants.DEFAULT_DOUBLE_PRESS_TIMEOUT_MS)
            }
        }
    }

    private fun cancelPendingSingle() {
        pendingSingle?.let { handler.removeCallbacks(it) }
        pendingSingle = null
    }

    /** Runs [action] now, or once the proximity check clears; drops it if in a pocket. */
    private fun whenNotInPocket(action: () -> Unit) {
        when (inPocket) {
            false -> action()
            null -> deferred = action
            true -> {}
        }
    }

    private val pocketListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) =
            finishPocketCheck(event.values[0] < event.sensor.maximumRange)

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val pocketTimeout = Runnable { finishPocketCheck(false) }

    private fun startPocketCheck(enabled: Boolean) {
        stopPocketCheck()
        deferred = null
        val sensor = proximity
        if (!enabled || sensor == null) {
            inPocket = false
            return
        }
        inPocket = null
        sensorManager!!.registerListener(
            pocketListener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler
        )
        handler.postDelayed(pocketTimeout, Constants.PROXIMITY_TIMEOUT_MS)
    }

    private fun finishPocketCheck(near: Boolean) {
        stopPocketCheck()
        if (inPocket != null) return
        inPocket = near
        val action = deferred
        deferred = null
        if (near) {
            Log.d(Constants.TAG, "Proximity near, ignoring press")
            handler.removeCallbacks(longPress)
            cancelPendingSingle()
            wakeLock.release()
        } else {
            action?.invoke()
        }
    }

    private fun stopPocketCheck() {
        sensorManager?.unregisterListener(pocketListener)
        handler.removeCallbacks(pocketTimeout)
    }
}
