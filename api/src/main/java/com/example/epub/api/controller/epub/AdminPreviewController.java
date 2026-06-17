package com.example.epub.api.controller.epub;

import com.example.epub.api.exception.AuthFailure;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.core.model.epub.EpubModels.Actor;
import com.example.epub.core.model.epub.ReaderModels.Chapter;
import com.example.epub.core.model.epub.ReaderModels.Manifest;
import com.example.epub.core.service.epub.AdminPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class AdminPreviewController {
    private final AdminPreviewService preview;
    public AdminPreviewController(AdminPreviewService preview) {this.preview=preview;}
    private Actor actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");
        return new Actor(principal.userId(),principal.credentialEpoch());
    }
    private HttpHeaders headers() {
        var headers=new HttpHeaders();headers.setCacheControl("private, no-store");headers.setVary(List.of("Cookie"));headers.set("X-Content-Type-Options","nosniff");
        return headers;
    }
    @GetMapping("/api/v1/admin/books/{book}/versions/{version}/preview/manifest")
    public ResponseEntity<Manifest> manifest(@PathVariable UUID book,@PathVariable UUID version) {
        return ResponseEntity.ok().headers(headers()).body(preview.manifest(actor(),book,version));
    }
    @GetMapping("/api/v1/admin/books/{book}/versions/{version}/preview/chapters/{chapter}")
    public ResponseEntity<Chapter> chapter(@PathVariable UUID book,@PathVariable UUID version,@PathVariable UUID chapter) {
        return ResponseEntity.ok().headers(headers()).body(preview.chapter(actor(),book,version,chapter));
    }
    private ResponseEntity<StreamingResponseBody> deliver(UUID book,UUID version,String name,boolean cover,HttpServletRequest request) {
        var file=preview.file(actor(),book,version,name,cover);preview.verify(file);var object=file.object();var headers=headers();
        headers.set("Accept-Ranges","none");
        headers.set("Content-Security-Policy","default-src 'none'; script-src 'none'; object-src 'none'; img-src 'self'; font-src 'self'; style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'self'; sandbox allow-same-origin");
        headers.setContentType(MediaType.parseMediaType(object.media()));headers.setContentLength(object.size());
        if(name==null&&!cover)headers.set("Content-Disposition","attachment; filename=\"reader.epub\"");
        if(request.getMethod().equals("HEAD"))return ResponseEntity.ok().headers(headers).build();
        return ResponseEntity.ok().headers(headers).body(output->{try(var input=preview.open(file)) {input.transferTo(output);}});
    }
    @GetMapping("/api/v1/admin/books/{book}/versions/{version}/preview/reader.epub")
    public ResponseEntity<StreamingResponseBody> epub(@PathVariable UUID book,@PathVariable UUID version,HttpServletRequest request) {return deliver(book,version,null,false,request);}
    @GetMapping("/api/v1/admin/books/{book}/versions/{version}/preview/cover")
    public ResponseEntity<StreamingResponseBody> cover(@PathVariable UUID book,@PathVariable UUID version,HttpServletRequest request) {return deliver(book,version,null,true,request);}
    @GetMapping("/api/v1/admin/books/{book}/versions/{version}/preview/resources/{name}")
    public ResponseEntity<StreamingResponseBody> resource(@PathVariable UUID book,@PathVariable UUID version,@PathVariable String name,HttpServletRequest request) {return deliver(book,version,name,false,request);}
}
