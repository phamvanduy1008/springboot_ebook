package com.example.epub.api.controller.jobs;
import com.example.epub.core.service.jobs.JobService;
import com.example.epub.core.model.jobs.JobModels.Status;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.api.exception.AuthFailure;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.ResponseEntity;
@RestController
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class JobController {
    private final JobService jobs;
    public JobController(JobService jobs) {this.jobs=jobs;}
    @GetMapping("/api/v1/jobs/{id}") public ResponseEntity<Status> status(@PathVariable UUID id) {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null||!(auth.getPrincipal() instanceof SessionPrincipal principal))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");
        return ResponseEntity.ok().header("Cache-Control","no-store").body(jobs.status(principal.userId(),id));
    }
}
