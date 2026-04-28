package com.example.epub.core.service.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.core.repository.catalog.CatalogRepository;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
public class PublicCatalogService {
    private final CatalogRepository catalog;
    private final TransactionTemplate reads;
    public PublicCatalogService(CatalogRepository catalog,PlatformTransactionManager manager) {
        this.catalog=catalog;reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public CatalogPage<BookView> books(CatalogQuery input) {
        var query=CatalogValidation.query(input,false);
        return reads.execute(tx -> {var page=catalog.books(query,false);return new CatalogPage<>(catalog.views(page.items(),false),page.page(),page.size(),page.total());});
    }
    public BookView book(UUID id) {
        return reads.execute(tx -> catalog.views(List.of(catalog.find(id,false).orElseThrow(() -> new CatalogFailure(CatalogFailure.Reason.NOT_FOUND))),false).getFirst());
    }
    public Category category(UUID id) {return catalog.category(id,false).orElseThrow(() -> new CatalogFailure(CatalogFailure.Reason.NOT_FOUND));}
    public CatalogPage<BookView> categoryBooks(UUID id,CatalogQuery input) {
        return reads.execute(tx -> {category(id);return books(new CatalogQuery(input.q(),id,input.year(),input.sort(),input.page(),input.size(),null));});
    }
    public CatalogPage<Category> categories(String q,String group,String sort,int page,int size) {
        CatalogValidation.query(new CatalogQuery(q,null,null,null,page,size,null),false);
        String order=sort==null?"bookCount,desc":sort;
        if(!Set.of("bookCount,desc","name,asc","sortOrder,asc").contains(order))throw CatalogFailure.invalid("sort","Unsupported category sort");
        String grouping=CatalogValidation.text(group,"group",40,false);
        return reads.execute(tx -> catalog.categories(CatalogValidation.text(q,"q",200,false),grouping,order,page,size,false));
    }
    public CatalogHome home() {
        return reads.execute(tx -> {
            var recent=catalog.books(new CatalogQuery("",null,null,"updatedAt,desc",0,8,null),false);
            var books=catalog.views(recent.items(),false);var categories=catalog.categories(null,null,"bookCount,desc",0,8,false);
            return new CatalogHome(books.stream().limit(5).toList(),List.of(),books,List.of(),catalog.publicAuthors(6),categories.items(),recent.total(),categories.total());
        });
    }
    public CatalogFilters filters() {return reads.execute(tx -> new CatalogFilters(catalog.years(),catalog.groups()));}
}
