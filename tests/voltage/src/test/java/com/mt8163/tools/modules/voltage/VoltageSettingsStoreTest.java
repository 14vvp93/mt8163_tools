package com.mt8163.tools.modules.voltage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VoltageSettingsStoreTest {
    @Test
    public void acceptsZeroAndBoundaryOffsets() {
        assertTrue(VoltageSettingsStore.isValidOffset(0.0f));
        assertTrue(VoltageSettingsStore.isValidOffset(-0.0f));
        assertTrue(VoltageSettingsStore.isValidOffset(-2.0f));
        assertTrue(VoltageSettingsStore.isValidOffset(2.0f));
    }

    @Test
    public void acceptsOffsetsInsideRange() {
        assertTrue(VoltageSettingsStore.isValidOffset(-1.25f));
        assertTrue(VoltageSettingsStore.isValidOffset(0.75f));
        assertTrue(VoltageSettingsStore.isValidOffset(Float.MIN_VALUE));
        assertTrue(VoltageSettingsStore.isValidOffset(-Float.MIN_VALUE));
        assertTrue(VoltageSettingsStore.isValidOffset(Math.nextUp(-2.0f)));
        assertTrue(VoltageSettingsStore.isValidOffset(Math.nextDown(2.0f)));
    }

    @Test
    public void rejectsOffsetsImmediatelyOutsideRange() {
        assertFalse(VoltageSettingsStore.isValidOffset(Math.nextDown(-2.0f)));
        assertFalse(VoltageSettingsStore.isValidOffset(Math.nextUp(2.0f)));
        assertFalse(VoltageSettingsStore.isValidOffset(-2.01f));
        assertFalse(VoltageSettingsStore.isValidOffset(2.01f));
    }

    @Test
    public void rejectsNanAndInfiniteOffsets() {
        assertFalse(VoltageSettingsStore.isValidOffset(Float.NaN));
        assertFalse(VoltageSettingsStore.isValidOffset(Float.POSITIVE_INFINITY));
        assertFalse(VoltageSettingsStore.isValidOffset(Float.NEGATIVE_INFINITY));
    }
}
