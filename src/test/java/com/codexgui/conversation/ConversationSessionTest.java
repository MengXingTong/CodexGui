package com.codexgui.conversation;

import com.codexgui.model.FileReference;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class ConversationSessionTest {
    @Test
    void retainsRemovedReferenceMetadataUntilTheDraftIsCleared() {
        var session = new ConversationSession(SessionId.of("session"));
        var reference = new FileReference("stable-id", "Source.cpp", Path.of("D:/Project/Source.cpp"), false);

        session.addFileReference(reference);
        session.fileReferences().clear();

        assertEquals(reference, session.knownFileReference(reference.id()));
        session.clearFileReferences();
        assertNull(session.knownFileReference(reference.id()));
    }
}
