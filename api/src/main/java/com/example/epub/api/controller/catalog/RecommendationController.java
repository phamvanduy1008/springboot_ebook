package com.example.epub.api.controller.catalog;

import com.example.epub.api.dto.catalog.PublicCatalogResponses;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.core.service.catalog.RecommendationService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class RecommendationController {
    public record Item(PublicCatalogResponses.BookResponse book,String reasonCode,String reasonText) { }
    public record Response(List<Item> items) { }
    private final RecommendationService recommendations;
    public RecommendationController(RecommendationService recommendations) {this.recommendations=recommendations;}
    @GetMapping("/api/v1/recommendations")
    public Response get(@RequestParam(defaultValue="6") int limit,jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        var auth=SecurityContextHolder.getContext().getAuthentication();
        var user=auth!=null&&auth.getPrincipal() instanceof SessionPrincipal p?p.userId():null;
        return new Response(recommendations.recommend(user,limit).stream().map(s->new Item(PublicCatalogResponses.book(s.book()),s.reasonCode(),s.reasonText())).toList());
    }
}
