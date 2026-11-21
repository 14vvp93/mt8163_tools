package com.mt8163.tools.modules.voltage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class VoltageConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !VoltageSettingsStore.ACTION_CONFIG_REQUEST.equals(intent.getAction())) {
            return;
        }

        String token = intent.getStringExtra(VoltageSettingsStore.EXTRA_TOKEN);
        if (token == null || token.length() != 64) {
            return;
        }

        VoltageSettingsStore.saveSessionToken(context, token);

        Intent response = new Intent(VoltageSettingsStore.ACTION_CONFIG_RESPONSE);
        response.setPackage("android");
        response.putExtra(VoltageSettingsStore.EXTRA_TOKEN, token);
        response.putExtra(VoltageSettingsStore.EXTRA_ENABLED,
                VoltageSettingsStore.isModuleEnabled(context));
        response.putExtra(VoltageSettingsStore.EXTRA_OFFSET,
                VoltageSettingsStore.getOffset(context));
        context.sendBroadcast(response);
    }
}
