package com.example.epub.api.security;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GooglePolicyTest {
    @Test void expiredLinkReauthenticationAndAuthorizationRequestsAreRejectedAndCleared() {
        var request=new org.springframework.mock.web.MockHttpServletRequest();
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        request.addParameter("intent","link");request.addParameter("state","test-state");
        request.getSession().setAttribute(GoogleLinkIntent.PENDING,new GoogleLinkIntent(java.util.UUID.randomUUID(),java.time.Instant.EPOCH,java.time.Instant.now().minusSeconds(600)));
        var authorization=org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth").clientId("test").redirectUri("https://example.test/callback").state("test-state").build();
        var repository=new GoogleAuthorizationRequests();
        assertThrows(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class,() -> repository.saveAuthorizationRequest(authorization,request,response));
        var expired=org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.from(authorization)
                .attributes(values -> values.put("app.issuedAt",java.time.Instant.now().minusSeconds(600))).build();
        new org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository().saveAuthorizationRequest(expired,request,response);
        assertNull(repository.loadAuthorizationRequest(request));assertNull(repository.removeAuthorizationRequest(request,response));
        assertNull(new org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository().loadAuthorizationRequest(request));
        assertNull(request.getSession().getAttribute(GoogleLinkIntent.PENDING));
    }
    @Test void returnTargetsCannotEscapeTheConfiguredFrontend() {
        assertEquals("/account",GoogleAuthorizationRequests.safeReturnTo("/account"));
        for(String value:List.of("//evil.example","https://evil.example","/\\evil.example","/%2f%2fevil.example","/account?next=https://evil.example","javascript:alert(1)"))
            assertEquals("/",GoogleAuthorizationRequests.safeReturnTo(value));
    }
}
