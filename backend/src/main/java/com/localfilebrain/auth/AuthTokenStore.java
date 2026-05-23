package com.localfilebrain.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory holder for the user's current proxy JWT.
 *
 * The desktop UI captures the JWT via Google OAuth (or the email stub) on
 * its side, persists it in the OS keychain via Electron's safeStorage, and
 * pushes it to this Java process at startup via POST /api/auth. The OpenAI
 * clients read from here whenever they need to attach an Authorization
 * header.
 *
 * Deliberately not persisted in this process — the Electron layer is the
 * single source of truth (it has secure storage; we don't). On Java restart
 * the UI re-pushes the token before any query.
 */
public final class AuthTokenStore {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenStore.class);

    private volatile String token;     // raw JWT, no "Bearer " prefix
    private volatile String userEmail; // for display / logging only

    public synchronized void setToken(String token, String userEmail) {
        this.token     = (token == null || token.isBlank()) ? null : token.trim();
        this.userEmail = userEmail;
        if (this.token == null) {
            log.info("Auth token cleared");
        } else {
            log.info("Auth token set for {}", userEmail == null ? "<unknown>" : userEmail);
        }
    }

    public synchronized void clear() { setToken(null, null); }

    public String getToken()      { return token; }
    public String getUserEmail()  { return userEmail; }
    public boolean isAuthenticated() { return token != null; }
}
