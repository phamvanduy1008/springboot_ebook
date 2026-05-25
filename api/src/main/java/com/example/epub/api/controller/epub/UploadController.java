package com.example.epub.api.controller.epub;
import com.example.epub.core.service.epub.*;
import com.example.epub.core.model.epub.EpubModels.*;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.api.exception.AuthFailure;
import java.util.UUID;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
@RestController
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class UploadController {
    private final UploadService uploads;
    public UploadController(UploadService uploads) {this.uploads=uploads;}
    private Actor actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal p))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");return new Actor(p.userId(),p.credentialEpoch());
    }
    private ResponseEntity<Accepted> upload(HttpServletRequest request,UUID book,boolean publicUpload,long revision,UUID key,String title,String author,String rights) throws IOException {
        var actor=actor();if(request.getContentLengthLong()>UploadService.MAX_UPLOAD_BYTES)throw new EpubFailure(EpubFailure.Reason.TOO_LARGE);
        var result=uploads.upload(actor,book,publicUpload,revision,key,title,author,rights,request.getInputStream());return ResponseEntity.accepted().header("Cache-Control","no-store").header("Location","/api/v1/jobs/"+result.jobId()).body(result);
    }
    @PostMapping(value="/api/v1/admin/books/{book}/epub",consumes="application/epub+zip")
    public ResponseEntity<Accepted> publicUpload(@PathVariable UUID book,@RequestParam long revision,@RequestHeader("Idempotency-Key") UUID key,@RequestParam(defaultValue="") String rightsNote,HttpServletRequest request) throws IOException {return upload(request,book,true,revision,key,"","",rightsNote);}
    @PutMapping(value="/api/v1/library/books/{book}/epub",consumes="application/epub+zip")
    public ResponseEntity<Accepted> privateUpload(@PathVariable UUID book,@RequestParam long revision,@RequestHeader("Idempotency-Key") UUID key,@RequestParam(defaultValue="") String rightsNote,HttpServletRequest request) throws IOException {return upload(request,book,false,revision,key,"","",rightsNote);}
    @PostMapping(value="/api/v1/imports",consumes="application/epub+zip")
    public ResponseEntity<Accepted> privateImport(@RequestHeader("Idempotency-Key") UUID key,@RequestParam(defaultValue="EPUB riêng tư") String title,@RequestParam(defaultValue="Chưa xác định") String author,HttpServletRequest request) throws IOException {return upload(request,null,false,0,key,title,author,"");}
}
