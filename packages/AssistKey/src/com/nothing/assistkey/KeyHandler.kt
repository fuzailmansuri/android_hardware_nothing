/*
 * Copyright (C) 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing.assistkey

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import com.android.internal.os.DeviceKeyHandler

class KeyHandler(private val context: Context) : DeviceKeyHandler {
    private val actionExecutor = ActionExecutor(context)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val telecomManager = context.getSystemService(TelecomManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isCameraShutterIntercepted = false
    private var isCallSilenceIntercepted = false

    private fun isRinging(): Boolean {
        return try {
            if (audioManager?.mode == AudioManager.MODE_RINGTONE) {
                return true
            }
            telecomManager?.isRinging == true
        } catch (_: Exception) {
            false
        }
    }

    private fun silenceRinger() {
        try {
            actionExecutor.vibrate(VibrationEffect.EFFECT_TICK)
            telecomManager?.silenceRinger()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Failed to silence ringer", e)
        }
    }

    private var cachedCameraPackages: Set<String>? = null
    private var lastCameraPackagesQueryTime: Long = 0L

    private fun isCameraInForeground(): Boolean {
        return try {
            val tasks = activityManager?.getRunningTasks(1)
            if (!tasks.isNullOrEmpty()) {
                val component = tasks[0].topActivity ?: return false
                val pkg = component.packageName.lowercase()
                val cls = component.className.lowercase()

                // Fast path: standard cameras and popular GCam port package signatures
                if (pkg.contains("camera") || cls.contains("camera") ||
                    pkg.contains("aperture") || pkg.contains("snapcam") ||
                    pkg.contains("mgc") || pkg.contains("gcam") ||
                    pkg.contains("scan3d") || pkg.contains("ruler") ||
                    pkg.contains("aweme") || pkg.contains("opencamera")) {
                    return true
                }

                // Dynamic fallback: verify if package handles camera capture intents
                val now = SystemClock.uptimeMillis()
                var cached = cachedCameraPackages
                if (cached == null || (now - lastCameraPackagesQueryTime) > 60_000L) {
                    val set = mutableSetOf<String>()
                    val pm = context.packageManager
                    val intents = listOf(
                        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                        Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                        Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    )
                    for (intent in intents) {
                        val resolveList = pm.queryIntentActivities(
                            intent,
                            PackageManager.ResolveInfoFlags.of(0L)
                        )
                        for (info in resolveList) {
                            set.add(info.activityInfo.packageName.lowercase())
                        }
                    }
                    cachedCameraPackages = set
                    lastCameraPackagesQueryTime = now
                    cached = set
                }
                cached.contains(pkg)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private val packageContext: Context = try {
        val pkgCtx = context.createPackageContext("com.nothing.assistkey", Context.CONTEXT_IGNORE_SECURITY)
        if (pkgCtx.isDeviceProtectedStorage) pkgCtx else pkgCtx.createDeviceProtectedStorageContext()
    } catch (_: Exception) {
        if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
    }

    private val sharedPreferences: SharedPreferences
        get() = packageContext.getSharedPreferences(
            Constants.SHARED_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

    private var isLongPressTriggered = false
    private var pendingSinglePressRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null

    private var isPocketNear = false
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var proximityListener: SensorEventListener? = null
    private var proximityTimeoutRunnable: Runnable? = null

    private fun getPrefBoolean(key: String, default: Boolean): Boolean {
        return try {
            Settings.System.getInt(context.contentResolver, key) == 1
        } catch (_: Settings.SettingNotFoundException) {
            val all = sharedPreferences.all
            when (val v = all[key]) {
                is Boolean -> v
                is Int -> v == 1
                is String -> v == "true" || v == "1"
                else -> default
            }
        } catch (_: Exception) {
            default
        }
    }

    private fun getPrefInt(key: String, default: Int): Int {
        return try {
            Settings.System.getInt(context.contentResolver, key)
        } catch (_: Settings.SettingNotFoundException) {
            val all = sharedPreferences.all
            when (val v = all[key]) {
                is Int -> v
                is String -> v.toIntOrNull() ?: default
                is Number -> v.toInt()
                else -> default
            }
        } catch (_: Exception) {
            default
        }
    }

    private fun getPrefString(key: String, default: String? = null): String? {
        return try {
            Settings.System.getString(context.contentResolver, key)
                ?: when (val v = sharedPreferences.all[key]) {
                    is String -> v
                    null -> default
                    else -> v.toString()
                }
        } catch (_: Exception) {
            when (val v = sharedPreferences.all[key]) {
                is String -> v
                null -> default
                else -> v.toString()
            }
        }
    }

    override fun handleKeyEvent(event: KeyEvent): KeyEvent? {
        if (!isEssentialKey(event)) {
            return event
        }

        val enabled = getPrefBoolean(Constants.PREF_KEY_ENABLED, true)
        if (!enabled) {
            return event
        }

        Log.d(Constants.TAG, "KeyHandler intercepted key: action=${event.action}, keyCode=${event.keyCode}, scanCode=${event.scanCode}")

        val isInteractive = powerManager?.isInteractive == true

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                // Smart context: Silence ringer if an incoming call is ringing
                if (getPrefBoolean(Constants.PREF_SMART_CALL_SILENCE, true) && isRinging()) {
                    isCallSilenceIntercepted = true
                    return null
                }

                // Smart context: Camera shutter button when Camera app is in foreground
                if (isInteractive && getPrefBoolean(Constants.PREF_SMART_CAMERA_SHUTTER, true) && isCameraInForeground()) {
                    isCameraShutterIntercepted = true
                    actionExecutor.vibrate(VibrationEffect.EFFECT_CLICK)
                    actionExecutor.sendCameraKeyEvent(KeyEvent.ACTION_DOWN, 0)
                    return null
                }

                val allowScreenOff = getPrefBoolean(Constants.PREF_SCREEN_OFF_ALLOWED, true)
                if (!isInteractive && !allowScreenOff) {
                    return null
                }

                val mistouchPrevention = getPrefBoolean(Constants.PREF_MISTOUCH_PREVENTION, true)
                if (!isInteractive && mistouchPrevention) {
                    startProximityCheck()
                }
                onKeyDown()
            } else if (isCameraShutterIntercepted) {
                actionExecutor.sendCameraKeyEvent(KeyEvent.ACTION_DOWN, event.repeatCount)
                return null
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (isCallSilenceIntercepted) {
                isCallSilenceIntercepted = false
                silenceRinger()
                return null
            }

            if (isCameraShutterIntercepted) {
                isCameraShutterIntercepted = false
                actionExecutor.sendCameraKeyEvent(KeyEvent.ACTION_UP, 0)
                return null
            }

            val allowScreenOff = getPrefBoolean(Constants.PREF_SCREEN_OFF_ALLOWED, true)
            if (!isInteractive && !allowScreenOff) {
                return null
            }

            onKeyUp()
        }

        // Consume the key event so OS doesn't launch default assistant
        return null
    }

    override fun onPocketStateChanged(inPocket: Boolean) {
        isPocketNear = inPocket
    }

    private fun isEssentialKey(event: KeyEvent): Boolean {
        return event.keyCode == Constants.KEYCODE_ESSENTIAL_KEY ||
               event.keyCode == Constants.KEYCODE_ESSENTIAL_KEY_BUTTON1 ||
               event.scanCode == Constants.SCANCODE_ESSENTIAL_KEY
    }

    private fun startProximityCheck() {
        val sensor = proximitySensor ?: return
        isPocketNear = false

        stopProximityCheck()

        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${Constants.TAG}:ProximityCheck"
        )
        wakeLock?.acquire(Constants.PROXIMITY_TIMEOUT_MS + 100)
        proximityWakeLock = wakeLock

        val timeoutRunnable = Runnable {
            stopProximityCheck()
        }
        proximityTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, Constants.PROXIMITY_TIMEOUT_MS)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val maxRange = sensor.maximumRange
                if (event.values[0] < maxRange) {
                    isPocketNear = true
                    Log.d(Constants.TAG, "Essential Key press ignored due to proximity near")
                    cancelPendingActions()
                }
                stopProximityCheck()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        proximityListener = listener

        sensorManager?.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )
    }

    private fun stopProximityCheck() {
        proximityTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            proximityTimeoutRunnable = null
        }
        proximityListener?.let {
            sensorManager?.unregisterListener(it)
            proximityListener = null
        }
        proximityWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            proximityWakeLock = null
        }
    }

    private fun cancelPendingActions() {
        longPressRunnable?.let {
            mainHandler.removeCallbacks(it)
            longPressRunnable = null
        }
        pendingSinglePressRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingSinglePressRunnable = null
        }
        isLongPressTriggered = false
    }

    private fun onKeyDown() {
        if (isPocketNear) return
        isLongPressTriggered = false

        val longPressAction = getPrefInt(
            Constants.PREF_LONG_PRESS_ACTION,
            Constants.ACTION_TORCH
        )

        if (longPressAction != Constants.ACTION_NONE) {
            val customApp = getPrefString(Constants.PREF_CUSTOM_APP_LONG)
            val runnable = Runnable {
                if (isPocketNear) return@Runnable
                isLongPressTriggered = true
                pendingSinglePressRunnable?.let {
                    mainHandler.removeCallbacks(it)
                    pendingSinglePressRunnable = null
                }
                Log.d(Constants.TAG, "KeyHandler executing long press action: $longPressAction, app: $customApp")
                actionExecutor.execute(longPressAction, customApp, VibrationEffect.EFFECT_HEAVY_CLICK)
            }
            longPressRunnable = runnable
            mainHandler.postDelayed(runnable, Constants.DEFAULT_LONG_PRESS_TIMEOUT_MS)
        }
    }

    private fun onKeyUp() {
        longPressRunnable?.let {
            mainHandler.removeCallbacks(it)
            longPressRunnable = null
        }

        if (isPocketNear) {
            isPocketNear = false
            return
        }

        if (isLongPressTriggered) {
            isLongPressTriggered = false
            return
        }

        val doublePressAction = getPrefInt(
            Constants.PREF_DOUBLE_PRESS_ACTION,
            Constants.ACTION_CAMERA
        )

        if (doublePressAction != Constants.ACTION_NONE) {
            if (pendingSinglePressRunnable != null) {
                // Second click arrived within timeout -> Double press!
                mainHandler.removeCallbacks(pendingSinglePressRunnable!!)
                pendingSinglePressRunnable = null
                val customApp = getPrefString(Constants.PREF_CUSTOM_APP_DOUBLE)
                Log.d(Constants.TAG, "KeyHandler executing double press action: $doublePressAction, app: $customApp")
                actionExecutor.execute(doublePressAction, customApp, VibrationEffect.EFFECT_DOUBLE_CLICK)
            } else {
                // First click -> schedule single press
                val singlePressAction = getPrefInt(
                    Constants.PREF_SINGLE_PRESS_ACTION,
                    Constants.ACTION_VOICE_ASSISTANT
                )
                val customApp = getPrefString(Constants.PREF_CUSTOM_APP_SINGLE)

                val runnable = Runnable {
                    if (isPocketNear) return@Runnable
                    pendingSinglePressRunnable = null
                    Log.d(Constants.TAG, "KeyHandler executing single press action: $singlePressAction, app: $customApp")
                    actionExecutor.execute(singlePressAction, customApp, VibrationEffect.EFFECT_CLICK)
                }
                pendingSinglePressRunnable = runnable
                mainHandler.postDelayed(runnable, Constants.DEFAULT_DOUBLE_PRESS_TIMEOUT_MS)
            }
        } else {
            // Double press disabled -> execute single press immediately
            val singlePressAction = getPrefInt(
                Constants.PREF_SINGLE_PRESS_ACTION,
                Constants.ACTION_VOICE_ASSISTANT
            )
            val customApp = getPrefString(Constants.PREF_CUSTOM_APP_SINGLE)
            Log.d(Constants.TAG, "KeyHandler executing immediate single press action: $singlePressAction, app: $customApp")
            actionExecutor.execute(singlePressAction, customApp, VibrationEffect.EFFECT_CLICK)
        }
    }
}
