/*
* Copyright (C) 2018 The OmniROM Project
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

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import androidx.preference.PreferenceManager;

import org.neoteric.device.DeviceExtras.Constants;
import org.neoteric.device.DeviceExtras.DeviceExtras;

public class OTGTileService extends TileService {

    private final OTGModeSwitch.Listener chargingListener = charging -> updateState();

    private boolean bound = false;
    private OTGModeSwitch service;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((OTGModeSwitch.LocalBinder) binder).getService();
            bound = true;
            service.addListener(chargingListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (Constants.CONTEXT == null) Constants.CONTEXT = getApplicationContext();
        Intent intent = new Intent(this, OTGModeSwitch.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
        updateState();
    }

    @Override
    public void onStopListening() {
        if (bound) {
            service.removeListener(chargingListener);
            unbindService(connection);
            bound = false;
        }
    }

    private void updateState() {
        Tile mTile = getQsTile();
        if (mTile != null) {
            boolean available = OTGModeSwitch.isAvailable() && Constants.CONTEXT != null;
            if (available) { 
                boolean enabled = getEnabled();
                mTile.setSubtitle(enabled ?
                        getString(R.string.accessibility_quick_settings_on) :
                        getString(R.string.accessibility_quick_settings_off));
                mTile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            } else {
                mTile.setState(Tile.STATE_UNAVAILABLE);
                mTile.setSubtitle(getString(R.string.accessibility_quick_settings_unavailable));
            }
            mTile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        setEnabled(!getEnabled());
        updateState();
    }

    private boolean getEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(this).getBoolean(DeviceExtras.KEY_OTG_SWITCH, false);
    }

    private void setEnabled(boolean enabled) {
        OTGModeSwitch.setOTGMode(enabled);
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefs.edit().putBoolean(DeviceExtras.KEY_OTG_SWITCH, enabled).apply();
    }
}
