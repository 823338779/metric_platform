package com.metricplatform.identity.auth;

public final class LoginRateLimitException extends RuntimeException {
    public LoginRateLimitException() {
        super("Too many login attempts");
    }
}
