package com.example.epub.api.security;

import java.io.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionCompatibilityTest {
    @Test void readsPrincipalAndPendingGoogleLinkWrittenBeforePackageMigration() throws Exception {
        // Fixture captured from the pre-migration classes, using synthetic IDs/timestamps only.
        try (var input = new ObjectInputStream(getClass().getResourceAsStream("/compat/legacy-session.ser"))) {
            var principal = assertInstanceOf(SessionPrincipal.class, input.readObject());
            var link = assertInstanceOf(GoogleLinkIntent.class, input.readObject());
            var user = UUID.fromString("804b00e4-65f9-4e29-9658-5af0f1b0cdd7");
            var epoch = Instant.parse("2026-04-07T07:15:00Z");
            assertEquals(user, principal.userId());
            assertEquals(user.toString(), principal.getName());
            assertEquals(epoch, principal.credentialEpoch());
            assertEquals(user, link.userId());
            assertEquals(epoch, link.credentialEpoch());
            assertEquals(Instant.parse("2026-04-07T07:16:00Z"), link.issuedAt());
        }
    }

    @Test void newPrincipalRoundTripsWithOnlyIdentityAndCredentialEpoch() throws Exception {
        var expected = new SessionPrincipal(UUID.randomUUID(), Instant.now());
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(expected); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertEquals(expected, input.readObject());
        }
    }
}
