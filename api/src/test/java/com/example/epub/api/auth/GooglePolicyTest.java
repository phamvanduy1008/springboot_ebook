package com.example.epub.api.auth;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class GooglePolicyTest {
    @Test void returnTargetsCannotEscapeTheConfiguredFrontend() {
        assertEquals("/account",GoogleAuthorizationRequests.safeReturnTo("/account"));
        for(String value:List.of("//evil.example","https://evil.example","/\\evil.example","/%2f%2fevil.example","/account?next=https://evil.example","javascript:alert(1)"))
            assertEquals("/",GoogleAuthorizationRequests.safeReturnTo(value));
    }
    @Test void enabledGoogleRejectsMissingConfigurationWithoutLeakingValues() {
        var env=new MockEnvironment();
        var failure=assertThrows(IllegalStateException.class,() -> new GoogleConfiguration().googleClient(env));
        assertTrue(failure.getMessage().contains("configuration is missing"));
    }
}
