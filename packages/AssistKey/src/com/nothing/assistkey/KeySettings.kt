/*
 * Copyright (C) 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.nothing.assistkey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.util.Log

/**
 * In-memory copy of the Settings.System values written by the settings UI, so the key path
 * never touches the settings provider. Refreshed on change and on user switch.
 */
class KeySettings(private val context: Context, private val handler: Handler) {

    data class Values(
        val enabled: Boolean = true,
        val singleAction: Int = Constants.ACTION_VOICE_ASSISTANT,
        val doubleAction: Int = Constants.ACTION_CAMERA,
        val longAction: Int = Constants.ACTION_TORCH,
        val singleApp: String? = null,
        val doubleApp: String? = null,
        val longApp: String? = null,
        val haptics: Boolean = true,
        val pocketCheck: Boolean = true,
        val screenOff: Boolean = true,
        val callSilence: Boolean = true,
        val cameraShutter: Boolean = true,
    )

    @Volatile
    var current = Values()
        private set

    private var loaded = false

    init {
        handler.post {
            try {
                val observer = object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) = load()
                }
                KEYS.forEach {
                    context.contentResolver.registerContentObserver(
                        Settings.System.getUriFor(it), false, observer, UserHandle.USER_ALL
                    )
                }
                context.registerReceiverForAllUsers(
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) = load()
                    },
                    IntentFilter(Intent.ACTION_USER_SWITCHED), null, handler
                )
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Failed to observe settings", e)
            }
            load()
        }
    }

    /** Called from the handler thread before acting on a press. */
    fun ensureLoaded() {
        if (!loaded) load()
    }

    private fun load() {
        val cr = context.contentResolver
        val user = UserHandle.USER_CURRENT
        fun int(key: String, def: Int) = Settings.System.getIntForUser(cr, key, def, user)
        fun bool(key: String, def: Boolean) = int(key, if (def) 1 else 0) != 0
        fun app(key: String) = Settings.System.getStringForUser(cr, key, user)?.takeIf { it.isNotBlank() }

        val d = Values()
        try {
            current = Values(
                enabled = bool(Constants.PREF_KEY_ENABLED, d.enabled),
                singleAction = int(Constants.PREF_SINGLE_PRESS_ACTION, d.singleAction),
                doubleAction = int(Constants.PREF_DOUBLE_PRESS_ACTION, d.doubleAction),
                longAction = int(Constants.PREF_LONG_PRESS_ACTION, d.longAction),
                singleApp = app(Constants.PREF_CUSTOM_APP_SINGLE),
                doubleApp = app(Constants.PREF_CUSTOM_APP_DOUBLE),
                longApp = app(Constants.PREF_CUSTOM_APP_LONG),
                haptics = bool(Constants.PREF_HAPTIC_FEEDBACK, d.haptics),
                pocketCheck = bool(Constants.PREF_MISTOUCH_PREVENTION, d.pocketCheck),
                screenOff = bool(Constants.PREF_SCREEN_OFF_ALLOWED, d.screenOff),
                callSilence = bool(Constants.PREF_SMART_CALL_SILENCE, d.callSilence),
                cameraShutter = bool(Constants.PREF_SMART_CAMERA_SHUTTER, d.cameraShutter),
            )
            loaded = true
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Failed to load settings", e)
        }
    }

    companion object {
        private val KEYS = listOf(
            Constants.PREF_KEY_ENABLED,
            Constants.PREF_SINGLE_PRESS_ACTION,
            Constants.PREF_DOUBLE_PRESS_ACTION,
            Constants.PREF_LONG_PRESS_ACTION,
            Constants.PREF_CUSTOM_APP_SINGLE,
            Constants.PREF_CUSTOM_APP_DOUBLE,
            Constants.PREF_CUSTOM_APP_LONG,
            Constants.PREF_HAPTIC_FEEDBACK,
            Constants.PREF_MISTOUCH_PREVENTION,
            Constants.PREF_SCREEN_OFF_ALLOWED,
            Constants.PREF_SMART_CALL_SILENCE,
            Constants.PREF_SMART_CAMERA_SHUTTER,
        )
    }
}
