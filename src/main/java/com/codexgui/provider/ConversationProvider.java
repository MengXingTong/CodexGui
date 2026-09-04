package com.codexgui.provider;

import com.codexgui.conversation.TurnHandle;

import java.util.Set;

public interface ConversationProvider {
    String id();
    TurnHandle startTurn(TurnRequest request, TurnEventSink sink);
    boolean cancel(TurnHandle handle);
    ProviderHealth health();
    Set<ProviderCapability> capabilities();
}
