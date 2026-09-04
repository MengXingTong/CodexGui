package com.codexgui.conversation;

import com.codexgui.model.Attachment;
import com.codexgui.model.ConversationEntry;
import com.codexgui.model.FileReference;
import com.codexgui.settings.CodexSettingsState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConversationSession {
    private final SessionId id;
    private String threadId;
    private String providerTurnId;
    private TurnHandle activeTurn;
    private long generation;
    private String provider = CodexSettingsState.CODEX_CHANNEL;
    private String providerProfileId = CodexSettingsState.CODEX_LOCAL_PROVIDER_ID;
    private int providerRevision = 1;
    private String title = "新会话";
    private String pendingUserBody;
    private int pendingUserMessageCount;
    private boolean busy;
    private long usageUsedTokens;
    private long usageMaxTokens;
    private final List<Attachment> attachments = new ArrayList<>();
    private final List<FileReference> fileReferences = new ArrayList<>();
    private final List<ConversationEntry> transcript = new ArrayList<>();
    private final List<QueuedInput> queuedInputs = new ArrayList<>();

    public ConversationSession(SessionId id) {
        this.id = id;
    }

    public SessionId id() { return id; }
    public String threadId() { return threadId; }
    public void threadId(String value) { threadId = value; }
    public String providerTurnId() { return providerTurnId; }
    public long generation() { return generation; }
    public String provider() { return provider; }
    public void provider(String value) { provider = value; }
    public String providerProfileId() { return providerProfileId; }
    public void providerProfileId(String value) { providerProfileId = value; }
    public int providerRevision() { return providerRevision; }
    public void providerRevision(int value) { providerRevision = value; }
    public String title() { return title; }
    public void title(String value) { title = value; }
    public String pendingUserBody() { return pendingUserBody; }
    public void pendingUserBody(String value) { pendingUserBody = value; }
    public int pendingUserMessageCount() { return pendingUserMessageCount; }
    public void pendingUserMessageCount(int value) { pendingUserMessageCount = value; }
    public boolean busy() { return busy; }
    public void busy(boolean value) { busy = value; }
    public long usageUsedTokens() { return usageUsedTokens; }
    public void usageUsedTokens(long value) { usageUsedTokens = value; }
    public long usageMaxTokens() { return usageMaxTokens; }
    public void usageMaxTokens(long value) { usageMaxTokens = value; }
    public List<Attachment> attachments() { return attachments; }
    public List<FileReference> fileReferences() { return fileReferences; }
    public List<ConversationEntry> transcript() { return transcript; }
    public List<QueuedInput> queuedInputs() { return queuedInputs; }

    public TurnHandle beginTurn() {
        generation++;
        providerTurnId = null;
        activeTurn = new TurnHandle(id, new TurnId(UUID.randomUUID().toString()), generation);
        busy = true;
        return activeTurn;
    }

    public TurnHandle handle() {
        return activeTurn;
    }

    public void bindProviderTurn(String value) {
        providerTurnId = value;
    }

    public boolean matches(TurnHandle handle) {
        return activeTurn != null && activeTurn.equals(handle);
    }

    public void completeTurn(TurnHandle handle) {
        if (!matches(handle)) return;
        activeTurn = null;
        providerTurnId = null;
        busy = false;
    }

    public void invalidateTurn() {
        generation++;
        activeTurn = null;
        providerTurnId = null;
        busy = false;
    }

    public void clearConversation() {
        threadId = null;
        generation++;
        activeTurn = null;
        providerTurnId = null;
        pendingUserBody = null;
        pendingUserMessageCount = 0;
        busy = false;
        usageUsedTokens = 0;
        usageMaxTokens = 0;
        attachments.clear();
        fileReferences.clear();
        transcript.clear();
        queuedInputs.clear();
    }
}
