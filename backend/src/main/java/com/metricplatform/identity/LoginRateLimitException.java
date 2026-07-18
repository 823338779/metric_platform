package com.metricplatform.identity;

public final class LoginRateLimitException extends RuntimeException {
    public LoginRateLimitException() {
        super("Too many login attempts");
    }
}
