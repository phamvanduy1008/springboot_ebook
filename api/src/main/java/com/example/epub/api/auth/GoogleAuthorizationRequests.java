package com.example.epub.api.auth;

import jakarta.servlet.http.*;
import java.time.*;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public class GoogleAuthorizationRequests implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {
    private final HttpSessionOAuth2AuthorizationRequestRepository delegate=new HttpSessionOAuth2AuthorizationRequestRepository();
    public static final String RETURN_TO="google.returnTo";
    public static String safeReturnTo(String value) {
        return value!=null && value.length()<=512 && value.matches("/[A-Za-z0-9/_-]*") && !value.contains("//")?value:"/";
    }
    @Override public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        var loaded=delegate.loadAuthorizationRequest(request);
        if(loaded!=null) {
            Object issued=loaded.getAttribute("app.issuedAt");
            if(!(issued instanceof Instant at) || at.isBefore(Instant.now().minus(Duration.ofMinutes(5)))) {
                // load has no response argument; remove/clear purges it with the actual callback response.
                return null;
            }
        }
        return loaded;
    }
    @Override public void saveAuthorizationRequest(OAuth2AuthorizationRequest value,HttpServletRequest request,HttpServletResponse response) {
        GoogleLinkIntent link=null;
        if(value!=null) {
            var session=request.getSession(false);Object pending=session==null?null:session.getAttribute(GoogleLinkIntent.PENDING);
            if(session!=null)session.removeAttribute(GoogleLinkIntent.PENDING);
            if("link".equals(request.getParameter("intent"))) {
                if(!(pending instanceof GoogleLinkIntent intent) || !intent.valid())throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException("invalid_link_request");
                link=(GoogleLinkIntent)pending;
            }
        }
        final GoogleLinkIntent target=link;
        if(value!=null)value=OAuth2AuthorizationRequest.from(value).attributes(attrs -> {
            attrs.put("app.issuedAt",Instant.now());attrs.put(RETURN_TO,safeReturnTo(request.getParameter("returnTo")));
            if(target!=null)attrs.put(GoogleLinkIntent.ATTRIBUTE,target);
        }).build();
        delegate.saveAuthorizationRequest(value,request,response);
    }
    @Override public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,HttpServletResponse response) {
        var loaded=loadAuthorizationRequest(request);
        delegate.saveAuthorizationRequest(null,request,response);
        if(loaded!=null) {
            request.setAttribute(RETURN_TO,loaded.getAttribute(RETURN_TO));request.setAttribute(GoogleLinkIntent.ATTRIBUTE,loaded.getAttribute(GoogleLinkIntent.ATTRIBUTE));
        }
        return loaded;
    }
    public void clear(HttpServletRequest request,HttpServletResponse response) {delegate.saveAuthorizationRequest(null,request,response);}
}
