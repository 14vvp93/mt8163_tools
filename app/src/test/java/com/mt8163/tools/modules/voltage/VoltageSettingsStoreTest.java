package com.mt8163.tools.modules.voltage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VoltageSettingsStoreTest {
    @Test
    public void acceptsConfiguredOffsetRange() {
        assertTrue(VoltageSettingsStore.isValidOffset(-2.0f));
        assertTrue(VoltageSettingsStore.isValidOffset(0.0f));
        assertTrue(VoltageSettingsStore.isValidOffset(2.0f));
    }

    @Test
    public void rejectsValuesOutsideConfiguredRange() {
        assertFalse(VoltageSettingsStore.isValidOffset(-2.1f));
        assertFalse(VoltageSettingsStore.isValidOffset(2.1f));
        assertFalse(VoltageSettingsStore.isValidOffset(Float.NaN));
        assertFalse(VoltageSettingsStore.isValidOffset(Float.POSITIVE_INFINITY));
    }
}
