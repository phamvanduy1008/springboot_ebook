package com.example.epub.api.controller.catalog;
import com.example.epub.api.dto.catalog.OverviewResponse;
import com.example.epub.core.service.catalog.StoryOverviewService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/books")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class StoryOverviewController {
 private final StoryOverviewService service;public StoryOverviewController(StoryOverviewService service){this.service=service;}
 @ModelAttribute public void cache(jakarta.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 @GetMapping("/{id}/overview") public OverviewResponse overview(@PathVariable UUID id){return OverviewResponse.from(service.overview(id));}
}
