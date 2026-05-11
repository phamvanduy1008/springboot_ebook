package com.example.epub.api.controller.library;

import com.example.epub.api.dto.library.LibraryResponses;
import com.example.epub.api.exception.AuthFailure;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.core.model.library.LibraryModels.*;
import com.example.epub.core.service.catalog.CatalogFailure;
import com.example.epub.core.service.library.LibraryService;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class LibraryController {
    private final LibraryService library;
    public LibraryController(LibraryService library) {this.library=library;}
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","no-store");}
    private Actor actor() {var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null || !(auth.getPrincipal() instanceof SessionPrincipal principal))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");return new Actor(principal.userId(),principal.credentialEpoch());}
    private long revision(Map<String,Object> input,Set<String> fields) {
        if(!input.keySet().equals(fields) || !(input.get("revision") instanceof Number n) || !(n instanceof Integer || n instanceof Long) || n.longValue()<0)throw CatalogFailure.invalid("body","Only the documented fields and an integer revision are accepted");
        return ((Number)input.get("revision")).longValue();
    }
    @GetMapping({"/library","/favorites","/view-history"}) public LibraryResponses.Page list(jakarta.servlet.http.HttpServletRequest request,
        @RequestParam(defaultValue="all") String shelf,@RequestParam(defaultValue="all") String ownership,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="recent") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        String path=request.getRequestURI();String kind=path.endsWith("favorites")?"favorites":path.endsWith("view-history")?"history":"library";
        return LibraryResponses.page(library.list(actor(),kind,new Query(shelf,ownership,q,sort,page,size)));
    }
    @GetMapping("/library/{bookId}") public LibraryResponses.State state(@PathVariable UUID bookId) {return LibraryResponses.state(library.state(actor(),bookId));}
    @PutMapping("/library/{bookId}") public LibraryResponses.State save(@PathVariable UUID bookId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Map<String,Object> input) {
        long revision=revision(input,Set.of("shelf","revision"));if(!(input.get("shelf") instanceof String shelf))throw CatalogFailure.invalid("shelf","A shelf is required");
        return LibraryResponses.state(library.mutate(actor(),bookId,"save",shelf,revision,key));
    }
    @DeleteMapping("/library/{bookId}") public LibraryResponses.State unsave(@PathVariable UUID bookId,@RequestHeader("Idempotency-Key") UUID key,@RequestParam long revision) {return LibraryResponses.state(library.mutate(actor(),bookId,"unsave",null,revision,key));}
    @PutMapping("/favorites/{bookId}") public LibraryResponses.State favorite(@PathVariable UUID bookId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Map<String,Object> input) {return LibraryResponses.state(library.mutate(actor(),bookId,"favorite",null,revision(input,Set.of("revision")),key));}
    @DeleteMapping("/favorites/{bookId}") public LibraryResponses.State unfavorite(@PathVariable UUID bookId,@RequestHeader("Idempotency-Key") UUID key,@RequestParam long revision) {return LibraryResponses.state(library.mutate(actor(),bookId,"unfavorite",null,revision,key));}
    @PostMapping("/view-history/{bookId}") public LibraryResponses.State view(@PathVariable UUID bookId,@RequestHeader("Idempotency-Key") UUID key,@RequestBody Map<String,Object> input) {return LibraryResponses.state(library.mutate(actor(),bookId,"view",null,revision(input,Set.of("revision")),key));}
    @DeleteMapping("/view-history/{bookId}") public LibraryResponses.State unview(@PathVariable UUID bookId,@RequestHeader("Idempotency-Key") UUID key,@RequestParam long revision) {return LibraryResponses.state(library.mutate(actor(),bookId,"unview",null,revision,key));}
    @GetMapping("/settings") public LibraryResponses.Settings settings() {return LibraryResponses.settings(library.settings(actor()));}
    @PutMapping("/settings") public LibraryResponses.Settings settings(@RequestHeader("Idempotency-Key") UUID key,@RequestBody Map<String,Object> input) {
        long revision=revision(input,Set.of("theme","readerTheme","fontSize","lineHeight","readerFont","revision"));
        if(!(input.get("theme") instanceof String theme) || !(input.get("readerTheme") instanceof String readerTheme) || !(input.get("readerFont") instanceof String readerFont) || !(input.get("fontSize") instanceof Integer fontSize) || !(input.get("lineHeight") instanceof Number lineHeight))throw CatalogFailure.invalid("settings","Invalid setting types");
        return LibraryResponses.settings(library.settings(actor(),new Settings(theme,readerTheme,fontSize,lineHeight.doubleValue(),readerFont,revision),key));
    }
}
