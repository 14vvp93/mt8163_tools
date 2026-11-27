package com.mt8163.tools.modules;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;

import com.mt8163.tools.core.ModuleContract;

import java.util.List;

public interface ModuleUiContract {
    View createContentView(Activity activity, FrameLayout container,
                           List<ModuleContract> modules);

    default void onEnabledChanged(Activity activity, boolean enabled) {
    }

    default void onContentRemoved() {
    }

    default boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        return false;
    }

    default boolean recreateContentOnEnabledChange() {
        return false;
    }
}
