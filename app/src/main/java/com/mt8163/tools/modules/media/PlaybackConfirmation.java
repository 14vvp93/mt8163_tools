package com.mt8163.tools.modules.media;

final class PlaybackConfirmation {
    private PlaybackConfirmation() {
    }

    static boolean startedBeforeDispatch(boolean playCommitted,
                                          long positionUpdateTime,
                                          long dispatchElapsedRealtime) {
        return !playCommitted || positionUpdateTime <= dispatchElapsedRealtime;
    }
}
