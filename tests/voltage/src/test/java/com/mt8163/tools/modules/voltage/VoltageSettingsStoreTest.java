package com.mt8163.tools.modules.voltage;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class VoltageSettingsStoreTest {
    private Context context;
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;

    @Before
    public void setUp() {
        context = mock(Context.class);
        preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);

        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences("voltage_settings", Context.MODE_PRIVATE))
                .thenReturn(preferences);
        when(context.getSharedPreferences("mt8163_tools", Context.MODE_PRIVATE))
                .thenReturn(preferences);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putFloat("voltage_offset", 1.5f)).thenReturn(editor);
        when(editor.commit()).thenReturn(true);
    }

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

    @Test
    public void returnsZeroWhenNoOffsetIsStored() {
        when(preferences.getFloat("voltage_offset", 0.0f)).thenReturn(0.0f);

        assertEquals(0.0f, VoltageSettingsStore.getOffset(context), 0.0f);
    }

    @Test
    public void returnsZeroForInvalidStoredOffsets() {
        when(preferences.getFloat("voltage_offset", 0.0f)).thenReturn(Float.NaN);

        assertEquals(0.0f, VoltageSettingsStore.getOffset(context), 0.0f);
    }

    @Test
    public void savesValidOffset() {
        VoltageSettingsStore.saveOffset(context, 1.5f);

        verify(editor).putFloat("voltage_offset", 1.5f);
        verify(editor).commit();
    }

    @Test
    public void doesNotSaveInvalidOffset() {
        VoltageSettingsStore.saveOffset(context, Float.POSITIVE_INFINITY);

        verify(preferences, never()).edit();
    }

    @Test
    public void readsModuleEnabledState() {
        when(preferences.getBoolean("module_voltage", false)).thenReturn(true);

        assertTrue(VoltageSettingsStore.isModuleEnabled(context));
    }
}
