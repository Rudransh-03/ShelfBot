package com.localfilebrain.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory holder for the device's current proxy JWT.
 *
 * Identity model (post-#11.5): each ShelfBot installation has a stable
 * device ID. The Electron layer registers that device with the proxy on
 * first launch, gets back a JWT, persists it via safeStorage, and pushes
 * it here over POST /api/auth so the OpenAI clients see it on every call.
 *
 * No email, no user record — the JWT alone identifies the device to the
 * proxy. This class is intentionally tiny: hold a string, hand it out
 * thread-safely, log when it changes.
 */
public final class AuthTokenStore {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenStore.class);

    private volatile String token; // raw JWT, no "Bearer " prefix

    public synchronized void setToken(String token) {
        this.token = (token == null || token.isBlank()) ? null : token.trim();
        if (this.token == null) {
            log.info("Auth token cleared");
        } else {
            log.info("Auth token set ({} chars)", this.token.length());
        }
    }

    public synchronized void clear() { setToken(null); }

    public String getToken()         { return token; }
    public boolean isAuthenticated() { return token != null; }
}
