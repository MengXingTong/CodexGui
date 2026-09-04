package com.codexgui.service;

import com.codexgui.settings.CodexSettingsState;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class AttentionService implements AutoCloseable {
    private final Project project;
    private final NotificationSoundPlayer soundPlayer = new NotificationSoundPlayer();

    public AttentionService(Project project) {
        this.project = project;
    }

    public CompletableFuture<Void> playConfigured(CodexSettingsState.StateData settings) {
        if (Objects.equals(settings.notificationSound, "custom") && settings.customSoundPath.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("请先选择自定义音频文件"));
        }
        return soundPlayer.play(settings.notificationSound, settings.customSoundPath);
    }

    public void notify(String title, String content, boolean notificationEnabled, boolean soundEnabled) {
        var settings = CodexSettingsState.getInstance().getState();
        var focused = isIdeFocused();
        // 系统通知和声音分别遵循聚焦策略，避免用户在 IDE 内工作时收到重复提醒。
        if (notificationEnabled && (!settings.systemNotificationOnlyWhenUnfocused || !focused)) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CodeDeck Notifications")
                .createNotification(title, content, NotificationType.INFORMATION)
                .notify(project);
        }
        if (soundEnabled && (!settings.soundOnlyWhenUnfocused || !focused)) {
            playConfigured(settings).exceptionally(error -> null);
        }
    }

    private boolean isIdeFocused() {
        var frame = WindowManager.getInstance().getFrame(project);
        return frame != null && frame.isFocused();
    }

    @Override
    public void close() {
        soundPlayer.close();
    }
}
