package com.example.epub.api.controller.epub;
import com.example.epub.core.service.epub.ReaderService;
import com.example.epub.core.model.epub.EpubModels.Actor;
import com.example.epub.core.model.epub.ReaderModels.*;
import com.example.epub.api.security.SessionPrincipal;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
@RestController
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ReaderController {
    private final ReaderService reader;
    public ReaderController(ReaderService reader) {this.reader=reader;}
    private Actor actor() {var auth=SecurityContextHolder.getContext().getAuthentication();return auth!=null&&auth.getPrincipal() instanceof SessionPrincipal p?new Actor(p.userId(),p.credentialEpoch()):null;}
    private HttpHeaders headers(boolean personal,String hash) {
        var headers=new HttpHeaders();headers.set("Cache-Control",personal?"private, no-store":"no-cache, max-age=0, must-revalidate");headers.set("Vary","Cookie");headers.setETag("\""+hash+"\"");headers.set("X-Content-Type-Options","nosniff");return headers;
    }
    private boolean matches(HttpServletRequest request,String etag) {
        String input=request.getHeader("If-None-Match");if(input==null)return false;for(String value:input.split(",")) {value=value.strip();if(value.equals("*")||value.equals(etag)||value.equals("W/"+etag))return true;}return false;
    }
    @GetMapping({"/api/v1/books/{book}/manifest","/api/v1/books/{book}/versions/{version}/manifest"})
    public ResponseEntity<?> manifest(@PathVariable UUID book,@PathVariable(required=false) UUID version,HttpServletRequest request) {
        var manifest=reader.manifest(actor(),book,version);var headers=headers(manifest.privateBook(),manifest.versionId().toString()+"-"+manifest.reader().sha256());
        return matches(request,headers.getETag())?ResponseEntity.status(304).headers(headers).build():ResponseEntity.ok().headers(headers).body(manifest);
    }
    private ResponseEntity<StreamingResponseBody> deliver(UUID book,UUID version,String name,boolean cover,HttpServletRequest request) {
        var file=reader.file(actor(),book,version,name,cover);reader.verify(file);var object=file.object();var headers=headers(file.version().privateBook(),object.hash());headers.set("Accept-Ranges","none");
        headers.set("Content-Security-Policy","default-src 'none'; script-src 'none'; object-src 'none'; img-src 'self'; font-src 'self'; style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-same-origin");
        if(matches(request,headers.getETag()))return ResponseEntity.status(304).headers(headers).build();
        headers.setContentType(MediaType.parseMediaType(object.media()));headers.setContentLength(object.size());
        if(name==null&&!cover)headers.set("Content-Disposition","attachment; filename=\"reader.epub\"");
        if(request.getMethod().equals("HEAD"))return ResponseEntity.ok().headers(headers).build();
        return ResponseEntity.ok().headers(headers).body(output->{try(var input=reader.open(file)) {input.transferTo(output);}});
    }
    @GetMapping("/api/v1/books/{book}/versions/{version}/reader.epub") public ResponseEntity<StreamingResponseBody> epub(@PathVariable UUID book,@PathVariable UUID version,HttpServletRequest request) {return deliver(book,version,null,false,request);}
    @GetMapping({"/api/v1/books/{book}/cover","/api/v1/books/{book}/versions/{version}/cover"}) public ResponseEntity<StreamingResponseBody> cover(@PathVariable UUID book,@PathVariable(required=false) UUID version,HttpServletRequest request) {return deliver(book,version,null,true,request);}
    @GetMapping("/api/v1/books/{book}/versions/{version}/resources/{name}") public ResponseEntity<StreamingResponseBody> resource(@PathVariable UUID book,@PathVariable UUID version,@PathVariable String name,HttpServletRequest request) {return deliver(book,version,name,false,request);}
}
