package com.example.epub.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class GoogleConfigurationTest {
    @Test void enabledGoogleRejectsMissingConfigurationWithoutLeakingValues() {
        var failure = assertThrows(IllegalStateException.class,
                () -> new GoogleConfiguration().googleClient(new MockEnvironment()));
        assertTrue(failure.getMessage().contains("configuration is missing"));
    }
}
