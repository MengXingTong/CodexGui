package com.codexgui.provider;

@FunctionalInterface
public interface TurnEventSink {
    void accept(TurnEvent event);
}
