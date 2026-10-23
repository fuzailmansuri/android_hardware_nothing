/*
 * Copyright (C) 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing.assistkey

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserHandle
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.telecom.TelecomManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.Toast
import com.android.internal.util.ScreenshotHelper
import com.android.internal.widget.LockPatternUtils

/** Runs assist key actions. Every method is called on the KeyHandler thread. */
class ActionExecutor(
    private val context: Context,
    private val handler: Handler,
    private val settings: KeySettings,
) {
    private val activityManager by lazy { context.getSystemService(ActivityManager::class.java)!! }
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java)!! }
    private val cameraManager by lazy { context.getSystemService(CameraManager::class.java)!! }
    private val inputManager by lazy { context.getSystemService(InputManager::class.java)!! }
    private val keyguardManager by lazy { context.getSystemService(KeyguardManager::class.java)!! }
    private val notificationManager by lazy { context.getSystemService(NotificationManager::class.java)!! }
    private val powerManager by lazy { context.getSystemService(PowerManager::class.java)!! }
    private val vibrator by lazy { context.getSystemService(Vibrator::class.java)!! }
    private val screenshotHelper by lazy { ScreenshotHelper(context) }

    private var torchCameraId: String? = null
    private var torchOn = false
    private var torchCallbackRegistered = false

    private var cameraPackages = emptySet<String>()
    private var cameraPackagesTime = 0L
    private var shutterDownTime = 0L

    /** Called on every key down so torch state is known before the first toggle. */
    fun prepare() {
        if (torchCallbackRegistered) return
        try {
            cameraManager.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == torchCameraId) torchOn = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    if (cameraId == torchCameraId) torchOn = false
                }
            }, handler)
            torchCameraId = findTorchCamera()
            torchCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Torch callback not registered yet", e)
        }
    }

    fun run(action: Int, app: String?, effect: Int) {
        if (action == Constants.ACTION_NONE) return
        Log.d(Constants.TAG, "Running action $action")
        haptic(effect)
        try {
            when (action) {
                Constants.ACTION_VOICE_ASSISTANT -> launchAssistant()
                Constants.ACTION_TORCH -> toggleTorch()
                Constants.ACTION_CAMERA -> launchCamera()
                Constants.ACTION_SCREENSHOT ->
                    screenshotHelper.takeScreenshot(SCREENSHOT_KEY_OTHER, handler, null)
                Constants.ACTION_RINGER_MODE -> cycleRingerMode()
                Constants.ACTION_DND -> toggleDnd()
                Constants.ACTION_VOICE_RECORDER -> launchRecorder()
                Constants.ACTION_MEDIA_PLAY_PAUSE -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                Constants.ACTION_MEDIA_NEXT -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                Constants.ACTION_MEDIA_PREV -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                Constants.ACTION_LAUNCH_APP -> launchApp(app)
                Constants.ACTION_AUTO_ROTATE -> toggleAutoRotate()
                Constants.ACTION_MUTE_MIC -> toggleMicMute()
                Constants.ACTION_LOCKDOWN -> lockdown()
                Constants.ACTION_WALLET -> launchWallet()
                Constants.ACTION_CAMERA_SHUTTER -> {
                    injectCameraKey(KeyEvent.ACTION_DOWN)
                    injectCameraKey(KeyEvent.ACTION_UP)
                }
                else -> Log.w(Constants.TAG, "Unknown action $action")
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Action $action failed", e)
        }
    }

    fun haptic(effect: Int) {
        if (!settings.current.haptics) return
        vibrator.vibrate(
            VibrationEffect.createPredefined(effect),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
        )
    }

    fun isRinging(): Boolean = try {
        context.getSystemService(TelecomManager::class.java)?.isRinging == true
    } catch (e: Exception) {
        false
    }

    fun silenceRinger() {
        haptic(VibrationEffect.EFFECT_TICK)
        try {
            context.getSystemService(TelecomManager::class.java)?.silenceRinger()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Failed to silence ringer", e)
        }
    }

    /** True when the focused task belongs to an app that handles camera capture intents. */
    fun isCameraInForeground(): Boolean = try {
        val top = activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        top != null && top in cameraPackages()
    } catch (e: Exception) {
        false
    }

    private fun cameraPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (cameraPackagesTime == 0L || now - cameraPackagesTime > CAMERA_PACKAGES_TTL_MS) {
            cameraPackages = listOf(
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA,
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE,
                MediaStore.ACTION_IMAGE_CAPTURE,
                MediaStore.ACTION_VIDEO_CAPTURE,
            ).flatMap { action ->
                context.packageManager.queryIntentActivities(
                    Intent(action), PackageManager.ResolveInfoFlags.of(0L)
                ).map { it.activityInfo.packageName }
            }.toSet()
            cameraPackagesTime = now
        }
        return cameraPackages
    }

    fun injectCameraKey(action: Int) {
        val now = SystemClock.uptimeMillis()
        if (action == KeyEvent.ACTION_DOWN) shutterDownTime = now
        val event = KeyEvent(
            shutterDownTime, now, action, KeyEvent.KEYCODE_CAMERA, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD
        )
        inputManager.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    private fun wake() {
        if (!powerManager.isInteractive) {
            powerManager.wakeUp(
                SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE, Constants.TAG
            )
        }
    }

    private fun start(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return try {
            context.startActivityAsUser(intent, UserHandle.CURRENT)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(Constants.TAG, "No activity for $intent")
            false
        }
    }

    private fun resolves(intent: Intent) =
        context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null

    private fun launchAssistant() {
        wake()
        // Same path as the framework assist gesture: the default assistant's session UI,
        // shown over the keyguard when allowed
        val searchManager = context.getSystemService(SearchManager::class.java)
        if (searchManager != null) {
            searchManager.launchAssist(Bundle())
        } else {
            start(Intent(Intent.ACTION_VOICE_COMMAND))
        }
    }

    private fun findTorchCamera(): String? {
        val ids = cameraManager.cameraIdList
        fun hasFlash(id: String) = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        fun isBack(id: String) = cameraManager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        return ids.firstOrNull { isBack(it) && hasFlash(it) } ?: ids.firstOrNull { hasFlash(it) }
    }

    private fun toggleTorch() {
        val id = torchCameraId ?: findTorchCamera()?.also { torchCameraId = it } ?: return
        cameraManager.setTorchMode(id, !torchOn)
    }

    private fun launchCamera() {
        wake()
        val secure = keyguardManager.isKeyguardLocked
        val intent = Intent(
            if (secure) MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
            else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        )
        if (secure && !resolves(intent)) {
            intent.action = MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        start(intent)
    }

    private fun cycleRingerMode() {
        audioManager.ringerModeInternal = when (audioManager.ringerModeInternal) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
    }

    private fun toggleDnd() {
        val mode = if (notificationManager.zenMode == Settings.Global.ZEN_MODE_OFF) {
            Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
        } else {
            Settings.Global.ZEN_MODE_OFF
        }
        notificationManager.setZenMode(mode, null, Constants.TAG)
    }

    private fun launchRecorder() {
        wake()
        val pm = context.packageManager
        val intent = RECORDER_PACKAGES.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
            ?: Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)
        start(intent)
    }

    private fun mediaKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun launchApp(packageName: String?) {
        if (packageName.isNullOrBlank()) return
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(Constants.TAG, "No launch intent for $packageName")
            return
        }
        wake()
        start(intent)
    }

    private fun toggleAutoRotate() {
        val cr = context.contentResolver
        val key = Settings.System.ACCELEROMETER_ROTATION
        val enabled = Settings.System.getIntForUser(cr, key, 0, UserHandle.USER_CURRENT) != 0
        Settings.System.putIntForUser(cr, key, if (enabled) 0 else 1, UserHandle.USER_CURRENT)
    }

    private fun toggleMicMute() {
        val muted = !audioManager.isMicrophoneMute
        audioManager.isMicrophoneMute = muted
        try {
            val res = context.createPackageContext(PACKAGE, 0)
            val text = res.getString(if (muted) R.string.mic_muted else R.string.mic_unmuted)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Failed to show mic toast", e)
        }
    }

    private fun lockdown() {
        LockPatternUtils(context).requireStrongAuth(
            LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
            UserHandle.USER_ALL
        )
        powerManager.goToSleep(SystemClock.uptimeMillis())
    }

    private fun launchWallet() {
        val intent = QuickAccessWalletClient.create(context).createWalletIntent()
            ?: WALLET_PACKAGES.firstNotNullOfOrNull {
                context.packageManager.getLaunchIntentForPackage(it)
            }
            ?: return
        wake()
        start(intent)
    }

    companion object {
        private const val PACKAGE = "com.nothing.assistkey"

        // WindowManager.ScreenshotSource.SCREENSHOT_KEY_OTHER
        private const val SCREENSHOT_KEY_OTHER = 2

        private const val CAMERA_PACKAGES_TTL_MS = 60_000L

        private val RECORDER_PACKAGES = listOf(
            "com.nothing.soundrecorder",
            "org.lineageos.recorder",
            "com.google.android.apps.recorder",
            "com.android.soundrecorder",
        )

        private val WALLET_PACKAGES = listOf(
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user",
        )
    }
}
