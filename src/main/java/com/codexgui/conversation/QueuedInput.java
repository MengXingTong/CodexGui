package com.codexgui.conversation;

import com.codexgui.model.Attachment;
import com.codexgui.model.FileReference;

import java.util.List;

public record QueuedInput(
    String inputText,
    String display,
    List<Attachment> attachments,
    List<FileReference> fileReferences
) {
    public QueuedInput {
        inputText = inputText == null ? "" : inputText;
        display = display == null ? "" : display;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
    }
}
