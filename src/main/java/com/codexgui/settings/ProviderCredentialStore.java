package com.codexgui.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;

public final class ProviderCredentialStore {
    private static final String SERVICE_NAME = "Codex GUI 供应商";

    private ProviderCredentialStore() {
    }

    public static String get(String providerId) {
        return PasswordSafe.getInstance().getPassword(attributes(providerId));
    }

    public static boolean has(String providerId) {
        var password = get(providerId);
        return password != null && !password.isBlank();
    }

    public static void set(String providerId, String apiKey) {
        PasswordSafe.getInstance().setPassword(attributes(providerId), apiKey == null || apiKey.isBlank() ? null : apiKey);
    }

    public static void remove(String providerId) {
        PasswordSafe.getInstance().setPassword(attributes(providerId), null);
    }

    private static CredentialAttributes attributes(String providerId) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE_NAME, providerId));
    }
}
