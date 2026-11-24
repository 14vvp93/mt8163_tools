package com.mt8163.tools;

import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class UiPreviewTest {
    private static final String PACKAGE = "com.mt8163.tools";
    private static final long UI_TIMEOUT = 5000L;

    private UiDevice device;
    private File output;

    @Before
    public void launchApplication() throws IOException {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File external = target.getExternalFilesDir(null);
        if (external == null) throw new IOException("External files directory is unavailable");
        output = new File(external, "ui-preview");
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Unable to create screenshot directory");
        }

        Intent launch = target.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        assertNotNull("Application launcher activity is missing", launch);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        target.startActivity(launch);
        assertTrue("Main screen did not appear",
                device.wait(Until.hasObject(By.text("Events")), UI_TIMEOUT));
        device.waitForIdle();
    }

    @Test
    public void captureAllScreensAndDialogs() throws IOException {
        capture("01-main");

        openModule("Media");
        capture("02-media");
        captureHeaderDisabledAndEnabled("media");
        captureToggleState("media_restore_background_media", "03-media-background-player");
        captureToggleState("media_restore_background_bluetooth", "04-media-background-bluetooth");

        openModule("Protection");
        captureHeaderDisabledAndEnabled("protection");
        clickId("protection_add_application");
        capture("05-protection-picker-default");
        clickText("Display system applications");
        capture("06-protection-picker-system-apps");
        UiObject2 search = waitFor(By.clazz("android.widget.EditText"));
        search.click();
        search.setText("Settings");
        assertNotNull("Settings application was not found",
                device.wait(Until.findObject(By.text("Settings")), UI_TIMEOUT));
        capture("07-protection-picker-search");
        clickText("Settings");
        capture("08-protection-application-added");
        clickDescription("Application settings");
        capture("09-protection-application-settings");
        clickText("Protect from Memory Cleaner");
        clickText("Hide application card in Recents");
        clickText("Allow closing the card via X");
        capture("10-protection-application-settings-enabled");
        clickText("Cancel");
        clickDescription("Remove application");
        capture("11-protection-remove-confirmation");
        clickText("Cancel");
        capture("12-protection-list");

        openModule("Voltage");
        captureHeaderDisabledAndEnabled("voltage");
        capture("14-voltage");

        openModule("Autorun");
        captureHeaderDisabledAndEnabled("autorun");
        capture("17-autorun");

        openModule("Events");
        capture("20-events");
        clickId("events_filter");
        capture("21-events-filter-menu");
        device.pressBack();
        clickId("events_clear");
        capture("22-events-clear-confirmation");
        clickText("Скасувати");
        clickId("events_export");
        capture("23-events-export-menu");
        clickText("Поточний фільтр");
        assertTrue("System document picker did not open",
                device.wait(Until.hasObject(By.textContains("Recent")), UI_TIMEOUT)
                        || device.wait(Until.hasObject(By.textContains("Save")), UI_TIMEOUT));
        capture("24-events-document-picker");
    }

    private void captureToggleState(String id, String name) throws IOException {
        UiObject2 toggle = waitFor(By.res(PACKAGE + ":id/" + id));
        capture(name + "-current");
        toggle.click();
        device.waitForIdle();
        capture(name + "-changed");
        waitFor(By.res(PACKAGE + ":id/" + id)).click();
        device.waitForIdle();
    }

    private void captureHeaderDisabledAndEnabled(String module) throws IOException {
        UiObject2 toggle = waitFor(By.res(PACKAGE + ":id/header_switch"));
        if (toggle.isChecked()) {
            toggle.click();
            device.waitForIdle();
            capture(module + "-disabled");
            toggle = waitFor(By.res(PACKAGE + ":id/header_switch"));
            toggle.click();
        } else {
            capture(module + "-disabled");
            toggle.click();
        }
        device.waitForIdle();
        capture(module + "-enabled");
    }

    private void openModule(String name) {
        clickText(name);
        assertNotNull("Module header did not appear: " + name,
                device.wait(Until.findObject(By.text(name)), UI_TIMEOUT));
        device.waitForIdle();
    }

    private void clickText(String text) {
        UiObject2 object = waitFor(By.text(text));
        object.click();
        device.waitForIdle();
    }

    private void clickDescription(String description) {
        UiObject2 object = waitFor(By.desc(description));
        object.click();
        device.waitForIdle();
    }

    private void clickId(String id) {
        UiObject2 object = waitFor(By.res(PACKAGE + ":id/" + id));
        object.click();
        device.waitForIdle();
    }

    private UiObject2 waitFor(BySelector selector) {
        UiObject2 object = device.wait(Until.findObject(selector), UI_TIMEOUT);
        assertNotNull("UI element not found: " + selector, object);
        return object;
    }

    private void capture(String name) throws IOException {
        device.waitForIdle();
        File screenshot = new File(output, name + ".png");
        assertTrue("Unable to capture screenshot: " + name, device.takeScreenshot(screenshot));
        assertTrue("Screenshot is empty: " + name, screenshot.length() > 0);
    }
}
