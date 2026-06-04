package com.example.epub.api.controller.epub;
import com.example.epub.core.service.epub.*;
import com.example.epub.core.model.epub.EpubModels.Actor;
import com.example.epub.core.model.epub.PublicationModels.State;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.api.exception.AuthFailure;
import com.example.epub.api.filter.RequestIdFilter;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
@RestController
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class PublicationController {
    private final PublicationService publication;
    public PublicationController(PublicationService publication) {this.publication=publication;}
    private Actor actor() {var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal p))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");return new Actor(p.userId(),p.credentialEpoch());}
    private long revision(Map<String,Object> body,boolean publish) {
        if(!body.keySet().equals(publish?Set.of("revision","versionId"):Set.of("revision"))||!(body.get("revision") instanceof Number n)||!(n instanceof Integer||n instanceof Long)||n.longValue()<0)throw new EpubFailure(EpubFailure.Reason.INVALID_INPUT);return n.longValue();
    }
    @PostMapping("/api/v1/admin/books/{book}/publish")
    public ResponseEntity<State> publish(@PathVariable UUID book,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        long revision=revision(body,true);UUID version;try {version=UUID.fromString((String)body.get("versionId"));}catch(RuntimeException invalid) {throw new EpubFailure(EpubFailure.Reason.INVALID_INPUT);}
        return ResponseEntity.ok().header("Cache-Control","no-store").body(publication.change(actor(),book,version,revision,key,true,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
    @PostMapping("/api/v1/admin/books/{book}/unpublish")
    public ResponseEntity<State> unpublish(@PathVariable UUID book,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(publication.change(actor(),book,null,revision(body,false),key,false,(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
