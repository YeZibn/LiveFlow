package com.liveflow.api.auth.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfTokensTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC);
    private static final String FIRST = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String NEXT = Base64.getEncoder().encodeToString(new byte[48]);

    @Test
    void enforcesPurposeSessionAndCanonicalEncoding() {
        var tokens = new CsrfTokens("first", FIRST, "", NOW);
        var anonymous = tokens.issueAnonymous();
        var session = UUID.randomUUID();
        String signed = tokens.issueSession(session);
        assertThat(tokens.validAnonymous(anonymous.preSession(), anonymous.token())).isTrue();
        assertThat(tokens.validAnonymous(anonymous.preSession(), signed)).isFalse();
        assertThat(tokens.validAnonymous(anonymous.preSession(), tokens.issueAnonymous().token())).isFalse();
        assertThat(tokens.validSession(signed, session)).isTrue();
        assertThat(tokens.validSession(signed, UUID.randomUUID())).isFalse();
        assertThat(tokens.validSession(anonymous.token(), session)).isFalse();
        assertThat(tokens.validSession(signed + "=", session)).isFalse();
        assertThat(tokens.validSession(signed.replace(".first.", ".unknown."), session)).isFalse();
    }

    @Test
    void expiryAndSharedVerificationKeyRotation() {
        var old = new CsrfTokens("first", FIRST, "", NOW);
        var anonymous = old.issueAnonymous();
        var session = UUID.randomUUID();
        String oldToken = old.issueSession(session);
        var rotated = new CsrfTokens("next", NEXT, "first=" + FIRST, NOW);
        var retired = new CsrfTokens("next", NEXT, "", NOW);
        assertThat(rotated.validAnonymous(anonymous.preSession(), anonymous.token())).isTrue();
        assertThat(rotated.validSession(oldToken, session)).isTrue();
        assertThat(retired.validSession(oldToken, session)).isFalse();
        assertThat(retired.validSession(rotated.issueSession(session), session)).isTrue();

        var atDeadline = new CsrfTokens("first", FIRST, "",
                Clock.offset(NOW, CsrfTokens.PRE_SESSION_LIFETIME));
        assertThat(atDeadline.validAnonymous(anonymous.preSession(), anonymous.token())).isFalse();
    }
}
