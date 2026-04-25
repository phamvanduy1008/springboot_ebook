package com.example.epub.api.controller.catalog;
import com.example.epub.api.dto.catalog.*;
import com.example.epub.api.dto.catalog.CatalogResponses.*;
import com.example.epub.api.dto.common.PageResult;
import com.example.epub.api.security.SessionPrincipal;
import com.example.epub.api.exception.AuthFailure;
import com.example.epub.api.filter.RequestIdFilter;
import com.example.epub.core.model.catalog.CatalogQuery;
import com.example.epub.core.service.catalog.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/admin")
@ConditionalOnProperty(name="app.persistence.enabled",havingValue="true")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class CatalogAdminController {
    private final CatalogAdminService catalog;
    public CatalogAdminController(CatalogAdminService catalog) {this.catalog=catalog;}
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse response) {response.setHeader("Cache-Control","no-store");}
    private UUID actor() {var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null || !(auth.getPrincipal() instanceof SessionPrincipal principal))throw new AuthFailure(401,"AUTHENTICATION_REQUIRED");return principal.userId();}
    private String requestId(HttpServletRequest request) {return (String)request.getAttribute(RequestIdFilter.ATTRIBUTE);}
    private long revision(Long value) {if(value==null)throw CatalogFailure.invalid("revision","Revision is required");return value;}
    @GetMapping("/books") public PageResult<AdminBookResponse> books(@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID category,@RequestParam(required=false) Integer year,@RequestParam(defaultValue="updatedAt,desc") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) String status) {return CatalogResponses.page(catalog.books(actor(),new CatalogQuery(q,category,year,sort,page,size,status)),CatalogResponses::admin);}
    @GetMapping("/books/{id}") public AdminBookResponse book(@PathVariable UUID id) {return CatalogResponses.admin(catalog.book(actor(),id));}
    @PostMapping("/books") public ResponseEntity<AdminBookResponse> create(@Valid @RequestBody BookInput input,HttpServletRequest request) {return ResponseEntity.status(201).body(CatalogResponses.admin(catalog.create(actor(),input.draft(),requestId(request))));}
    @PatchMapping("/books/{id}") public AdminBookResponse update(@PathVariable UUID id,@Valid @RequestBody BookInput input,HttpServletRequest request) {return CatalogResponses.admin(catalog.update(actor(),id,revision(input.revision()),input.draft(),requestId(request)));}
    @PostMapping("/books/{id}/clone") public ResponseEntity<AdminBookResponse> clone(@PathVariable UUID id,@Valid @RequestBody RevisionInput input,HttpServletRequest request) {return ResponseEntity.status(201).body(CatalogResponses.admin(catalog.clone(actor(),id,input.revision(),requestId(request))));}
    @PostMapping("/books/{id}/versions") public ResponseEntity<VersionResponse> version(@PathVariable UUID id,@Valid @RequestBody RevisionInput input,HttpServletRequest request) {return ResponseEntity.status(201).body(CatalogResponses.version(catalog.version(actor(),id,input.revision(),input.rightsNote(),requestId(request))));}
    @PostMapping("/books/{id}/hide") public AdminBookResponse hide(@PathVariable UUID id,@Valid @RequestBody RevisionInput input,HttpServletRequest request) {return CatalogResponses.admin(catalog.hide(actor(),id,input.revision(),requestId(request)));}
    @DeleteMapping("/books/{id}") public ResponseEntity<?> delete(@PathVariable UUID id,@Valid @RequestBody RevisionInput input,HttpServletRequest request) {catalog.delete(actor(),id,input.revision(),requestId(request));return ResponseEntity.noContent().build();}
    @GetMapping("/categories") public PageResult<CategoryResponse> categories(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return CatalogResponses.page(catalog.categories(actor(),page,size),CatalogResponses::category);}
    @PostMapping("/categories") public ResponseEntity<CategoryResponse> category(@Valid @RequestBody CategoryInput input,HttpServletRequest request) {return ResponseEntity.status(201).body(CatalogResponses.category(catalog.category(actor(),null,null,input.name(),input.slug(),input.groupName(),input.description(),input.color(),input.sortOrder()==null?0:input.sortOrder(),requestId(request))));}
    @PatchMapping("/categories/{id}") public CategoryResponse category(@PathVariable UUID id,@Valid @RequestBody CategoryInput input,HttpServletRequest request) {return CatalogResponses.category(catalog.category(actor(),id,revision(input.revision()),input.name(),input.slug(),input.groupName(),input.description(),input.color(),input.sortOrder()==null?0:input.sortOrder(),requestId(request)));}
    @DeleteMapping("/categories/{id}") public ResponseEntity<?> category(@PathVariable UUID id,@Valid @RequestBody RevisionInput input,HttpServletRequest request) {catalog.deleteCategory(actor(),id,input.revision(),requestId(request));return ResponseEntity.noContent().build();}
    @GetMapping("/authors") public PageResult<AuthorResponse> authors(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return CatalogResponses.page(catalog.authors(actor(),page,size),CatalogResponses::author);}
    @PostMapping("/authors") public ResponseEntity<AuthorResponse> author(@Valid @RequestBody AuthorInput input,HttpServletRequest request) {return ResponseEntity.status(201).body(CatalogResponses.author(catalog.author(actor(),null,null,input.name(),input.slug(),input.biography(),requestId(request))));}
    @PatchMapping("/authors/{id}") public AuthorResponse author(@PathVariable UUID id,@Valid @RequestBody AuthorInput input,HttpServletRequest request) {return CatalogResponses.author(catalog.author(actor(),id,revision(input.revision()),input.name(),input.slug(),input.biography(),requestId(request)));}
    @DeleteMapping("/authors/{id}") public ResponseEntity<?> author(@PathVariable UUID id,@Valid @RequestBody RevisionInput input,HttpServletRequest request) {catalog.deleteAuthor(actor(),id,input.revision(),requestId(request));return ResponseEntity.noContent().build();}
}
