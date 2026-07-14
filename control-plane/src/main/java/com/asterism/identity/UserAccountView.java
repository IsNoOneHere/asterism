package com.asterism.identity;

public record UserAccountView(String userId, String displayName, String email, boolean enabled) {
}

