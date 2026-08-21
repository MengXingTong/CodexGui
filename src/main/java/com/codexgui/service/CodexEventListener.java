package com.codexgui.service;

import com.google.gson.JsonObject;

public interface CodexEventListener {
    default void onConnectionChanged(boolean connected, String detail) {}
    default void onNotification(String method, JsonObject params) {}
    default void onServerRequest(long requestId, String method, JsonObject params) {}
    default void onProtocolError(String message, Throwable error) {}
}
