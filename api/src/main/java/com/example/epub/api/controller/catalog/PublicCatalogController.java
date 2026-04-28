package com.example.epub.api.controller.catalog;
import com.example.epub.api.dto.catalog.*;
import com.example.epub.api.dto.catalog.CatalogResponses.*;
import com.example.epub.api.dto.catalog.PublicCatalogResponses.*;
import com.example.epub.api.dto.common.PageResult;
import com.example.epub.core.model.catalog.CatalogQuery;
import com.example.epub.core.service.catalog.PublicCatalogService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class PublicCatalogController {
    private final PublicCatalogService catalog;
    public PublicCatalogController(PublicCatalogService catalog) {this.catalog=catalog;}
    @ModelAttribute public void cache(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","no-store");}
    @GetMapping("/books") public PageResult<BookResponse> books(@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID category,@RequestParam(required=false) Integer year,@RequestParam(defaultValue="updatedAt,desc") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return CatalogResponses.page(catalog.books(new CatalogQuery(q,category,year,sort,page,size,null)),PublicCatalogResponses::book);}
    @GetMapping("/books/{id}") public BookResponse book(@PathVariable UUID id) {return PublicCatalogResponses.book(catalog.book(id));}
    @GetMapping("/books/filters") public FiltersResponse filters() {var filters=catalog.filters();return new FiltersResponse(filters.years(),filters.groups());}
    @GetMapping("/categories") public PageResult<CategoryResponse> categories(@RequestParam(defaultValue="") String q,@RequestParam(required=false) String group,@RequestParam(defaultValue="bookCount,desc") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return CatalogResponses.page(catalog.categories(q,group,sort,page,size),CatalogResponses::category);}
    @GetMapping("/categories/{id}") public CategoryResponse category(@PathVariable UUID id) {return CatalogResponses.category(catalog.category(id));}
    @GetMapping("/categories/{id}/books") public PageResult<BookResponse> books(@PathVariable UUID id,@RequestParam(defaultValue="") String q,@RequestParam(required=false) Integer year,@RequestParam(defaultValue="updatedAt,desc") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return CatalogResponses.page(catalog.categoryBooks(id,new CatalogQuery(q,id,year,sort,page,size,null)),PublicCatalogResponses::book);}
    @GetMapping("/home") public HomeResponse home() {return PublicCatalogResponses.home(catalog.home());}
}
