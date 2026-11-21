package com.mt8163.tools.modules.voltage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.util.regex.Pattern;

public final class VoltageConfigReceiver extends BroadcastReceiver {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-f]{64}");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null
                || !VoltageSettingsStore.ACTION_CONFIG_REQUEST.equals(intent.getAction())) {
            return;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) return;
        Object tokenValue = extras.get(VoltageSettingsStore.EXTRA_TOKEN);
        if (!(tokenValue instanceof String)) return;

        String token = (String) tokenValue;
        if (!TOKEN_PATTERN.matcher(token).matches()) return;

        VoltageSettingsStore.saveSessionToken(context, token);

        Intent response = new Intent(VoltageSettingsStore.ACTION_CONFIG_RESPONSE);
        response.setPackage("android");
        response.putExtra(VoltageSettingsStore.EXTRA_TOKEN, token);
        response.putExtra(VoltageSettingsStore.EXTRA_ENABLED,
                VoltageSettingsStore.isModuleEnabled(context));
        response.putExtra(VoltageSettingsStore.EXTRA_OFFSET,
                VoltageSettingsStore.getOffset(context));
        try {
            context.sendBroadcast(response);
        } catch (RuntimeException ignored) {
        }
    }
}
