package com.codexgui.provider;

import com.codexgui.conversation.TurnHandle;
import com.codexgui.model.Attachment;
import com.codexgui.model.FileReference;
import com.codexgui.settings.CodexSettingsState;

import java.util.List;
import java.util.Objects;

public record TurnRequest(
    TurnHandle handle,
    String conversationId,
    String input,
    List<Attachment> attachments,
    List<FileReference> fileReferences,
    String model,
    String effort,
    String serviceTier,
    String approvalPolicy,
    String sandboxMode,
    String instructions,
    String executable,
    CodexSettingsState.ProviderProfileSnapshot providerProfile
) {
    public TurnRequest {
        Objects.requireNonNull(handle, "handle");
        conversationId = Objects.requireNonNullElse(conversationId, "");
        input = Objects.requireNonNullElse(input, "");
        attachments = List.copyOf(Objects.requireNonNullElse(attachments, List.of()));
        fileReferences = List.copyOf(Objects.requireNonNullElse(fileReferences, List.of()));
        model = Objects.requireNonNullElse(model, "");
        effort = Objects.requireNonNullElse(effort, "");
        serviceTier = Objects.requireNonNullElse(serviceTier, "");
        approvalPolicy = Objects.requireNonNullElse(approvalPolicy, "");
        sandboxMode = Objects.requireNonNullElse(sandboxMode, "");
        instructions = Objects.requireNonNullElse(instructions, "");
        executable = Objects.requireNonNullElse(executable, "");
    }
}
