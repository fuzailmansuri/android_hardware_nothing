/*
* Copyright (C) 2016 The OmniROM Project
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 2 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program. If not, see <http://www.gnu.org/licenses/>.
*
*/
package org.neoteric.device.DeviceExtras;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.IBinder;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.neoteric.device.DeviceExtras.FileUtils;

public class OTGModeSwitch extends Service implements OnPreferenceChangeListener {

    private SharedPreferences prefs;
    private boolean receiverRegistered = false;
    private static volatile boolean deviceCharging = false;

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public OTGModeSwitch getService() {
            return OTGModeSwitch.this;
        }
    }

    public interface Listener {
        void onChargingStateChanged(boolean charging);
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public synchronized void addListener(Listener listener) {
        listeners.add(listener);
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private final BroadcastReceiver chargingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            switch (status) {
                case BatteryManager.BATTERY_STATUS_CHARGING:
                case BatteryManager.BATTERY_STATUS_FULL:
                case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                    deviceCharging = true;
                    setOTGMode(false);
                    prefs.edit().putBoolean(DeviceExtras.KEY_OTG_SWITCH, false).apply();
                    break;
                case BatteryManager.BATTERY_STATUS_DISCHARGING:
                    deviceCharging = false;
                    break;
            }
            for (Listener l : listeners) {
                l.onChargingStateChanged(deviceCharging);
            }
        }
    };

    public static final String FILE = "/proc/charger/nt_otg_enable";

    public static boolean isSupported() {
        return FileUtils.fileWritable(FILE);
    }

    public static boolean isAvailable() {
        return (isSupported() && !deviceCharging);
    }

    private static void setOTGMode(boolean newState) {
        FileUtils.writeProcNode(FILE, newState ? "3" : "4");
    }
    
    private void setReceiver(boolean enable) {
        if (enable && receiverRegistered) return;
        if (enable) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(chargingReceiver, filter);
            receiverRegistered = true;
        } else {
            unregisterReceiver(chargingReceiver);
            receiverRegistered = false;
        }
    }

    public static boolean isCurrentlyEnabled() {
        return (isSupported() && FileUtils.getFileValueAsBoolean(FILE, false, "3", "4") && !deviceCharging);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Boolean enabled = (Boolean) newValue;
        setOTGMode(enabled);
        return true;
    }

    @Override
    public void onCreate() {
        Constants.CONTEXT = this.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(Constants.CONTEXT);
        setReceiver(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        setReceiver(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
