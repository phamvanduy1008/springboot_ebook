package com.example.epub.core.service.catalog;
import com.example.epub.core.model.catalog.*;
import com.example.epub.core.repository.catalog.*;
import com.example.epub.core.service.identity.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
public class CatalogAdminService {
    private final CatalogRepository catalog;
    private final CatalogAdminRepository changes;
    private final AccountService accounts;
    private final TransactionTemplate transactions;
    private final TransactionTemplate reads;
    private final EditorialRepository editorial;
    public CatalogAdminService(CatalogRepository catalog,CatalogAdminRepository changes,AccountService accounts,TransactionTemplate transactions,EditorialRepository editorial) {
        this.editorial=editorial;this.catalog=catalog;this.changes=changes;this.accounts=accounts;this.transactions=transactions;
        reads=new TransactionTemplate(transactions.getTransactionManager());reads.setReadOnly(true);reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private void admin(UUID actor) {AccessPolicy.requireAdministrator(accounts.byId(actor).orElse(null));}
    private <T> T mutate(UUID actor,Supplier<T> action) {
        try {return transactions.execute(tx -> {admin(actor);if(!changes.lockAdministrator(actor))throw new AccessPolicy.Denied(false);return action.get();});}
        catch(DataIntegrityViolationException conflict) {throw new CatalogFailure(CatalogFailure.Reason.REFERENCE_CONFLICT);}
    }
    private void audit(String action,UUID actor,String type,UUID target,String requestId) {
        String safe=requestId!=null && requestId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")?requestId:UUID.randomUUID().toString();changes.audit(action,actor,type,target,safe);
    }
    private CatalogBook lock(UUID book,long revision) {
        CatalogValidation.revision(revision);
        if(!changes.lockBook(book))throw new CatalogFailure(CatalogFailure.Reason.NOT_FOUND);
        var found=catalog.find(book,true).orElseThrow(() -> new CatalogFailure(CatalogFailure.Reason.NOT_FOUND));
        if(found.revision()!=revision)throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);return found;
    }
    private BookDraft draft(BookDraft input) {
        var value=CatalogValidation.draft(input);var names=new ArrayList<String>();
        for(UUID id:value.categoryIds())if(catalog.category(id,true).isEmpty())throw CatalogFailure.invalid("categoryIds","Unknown category");
        for(UUID id:value.authorIds())names.add(catalog.author(id).orElseThrow(() -> CatalogFailure.invalid("authorIds","Unknown author")).name());
        if(names.isEmpty())return value;
        String display=CatalogValidation.text(String.join(", ",names),"authorIds",500,true);
        return new BookDraft(value.title(),display,value.slug(),value.summary(),value.description(),value.language(),value.isTranslation(),value.publicationYear(),value.serialStatus(),value.authorIds(),value.categoryIds(),value.tags(),value.rightsNote());
    }
    public CatalogPage<BookView> books(UUID actor,CatalogQuery input) {
        return reads.execute(tx -> {admin(actor);var page=catalog.books(CatalogValidation.query(input,true),true);return new CatalogPage<>(catalog.views(page.items(),false),page.page(),page.size(),page.total());});
    }
    public BookView book(UUID actor,UUID id) {return reads.execute(tx -> {admin(actor);return view(id);});}
    private BookView view(UUID id) {return catalog.views(List.of(catalog.find(id,true).orElseThrow(() -> new CatalogFailure(CatalogFailure.Reason.NOT_FOUND))),true).getFirst();}
    public BookView create(UUID actor,BookDraft input,String requestId) {
        return mutate(actor,() -> {var value=draft(input);UUID id=UUID.randomUUID();changes.createBook(id,actor,value);changes.relations(id,value);changes.newVersion(id,value.rightsNote());audit("BOOK_CREATED",actor,"BOOK",id,requestId);return view(id);});
    }
    public BookView update(UUID actor,UUID id,long revision,BookDraft input,String requestId) {
        return mutate(actor,() -> {
            var current=lock(id,revision);var value=draft(input);
            if(input.rightsNote()!=null && !"UPLOADING".equals(current.versionState()))throw new CatalogFailure(CatalogFailure.Reason.INVALID_STATE);
            if(!changes.updateBook(id,revision,value))throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);
            changes.relations(id,value);if(input.rightsNote()!=null)changes.draftRights(id,value.rightsNote());
            audit("BOOK_UPDATED",actor,"BOOK",id,requestId);return view(id);
        });
    }
    public BookView clone(UUID actor,UUID id,long revision,String requestId) {
        return mutate(actor,() -> {lock(id,revision);var original=view(id);var book=original.book();
            var value=new BookDraft(book.title(),book.authorDisplay(),book.slug(),book.summary(),book.description(),book.language(),book.isTranslation(),book.publicationYear(),book.serialStatus(),original.authors().stream().map(Author::id).toList(),original.categories().stream().map(Category::id).toList(),original.tags(),"");
            UUID clone=UUID.randomUUID();changes.createBook(clone,actor,value);changes.relations(clone,value);changes.newVersion(clone,"");audit("BOOK_CLONED",actor,"BOOK",clone,requestId);return view(clone);});
    }
    public BookVersion version(UUID actor,UUID id,long revision,String rights,String requestId) {
        return mutate(actor,() -> {lock(id,revision);var version=changes.newVersion(id,CatalogValidation.text(rights,"rightsNote",4000,false));changes.touchBook(id,revision);audit("BOOK_VERSION_CREATED",actor,"BOOK",id,requestId);return version;});
    }
    public BookView hide(UUID actor,UUID id,long revision,String requestId) {
        return mutate(actor,() -> {lock(id,revision);if(catalog.find(id,false).isEmpty())throw new CatalogFailure(CatalogFailure.Reason.INVALID_STATE);if(!changes.hide(id,revision))throw new CatalogFailure(CatalogFailure.Reason.INVALID_STATE);audit("BOOK_HIDDEN",actor,"BOOK",id,requestId);return view(id);});
    }
    public void delete(UUID actor,UUID id,long revision,String requestId) {
        mutate(actor,() -> {lock(id,revision);changes.deleteBook(id,revision);audit("BOOK_DELETED",actor,"BOOK",id,requestId);return null;});
    }
    public CatalogPage<Category> categories(UUID actor,int page,int size) {return reads.execute(tx -> {admin(actor);CatalogValidation.query(new CatalogQuery(null,null,null,null,page,size,null),true);return catalog.categories(null,null,"sortOrder,asc",page,size,true);});}
    public Category category(UUID actor,UUID id,Long revision,String name,String slug,String group,String description,String color,int order,String requestId) {
        return mutate(actor,() -> {
            String title=CatalogValidation.text(name,"name",100,true),key=CatalogValidation.slug(slug,title,120);
            String grouping=CatalogValidation.text(group==null?"OTHER":group,"groupName",40,true),summary=CatalogValidation.text(description,"description",2000,false);
            String tint=color==null?"#3156D9":color;if(!tint.matches("#[0-9a-fA-F]{6}") || order<0)throw CatalogFailure.invalid("color","Invalid color or order");
            UUID target=id==null?UUID.randomUUID():id;
            if(id==null)changes.createCategory(target,title,key,grouping,summary,tint,order);
            else {CatalogValidation.revision(revision==null?-1:revision);if(!changes.lockCategory(id))throw new CatalogFailure(CatalogFailure.Reason.NOT_FOUND);
                if(!changes.updateCategory(id,revision,title,key,grouping,summary,tint,order))throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);}
            audit(id==null?"CATEGORY_CREATED":"CATEGORY_UPDATED",actor,"CATEGORY",target,requestId);return catalog.category(target,true).orElseThrow();
        });
    }
    public void deleteCategory(UUID actor,UUID id,long revision,String requestId) {
        mutate(actor,() -> {CatalogValidation.revision(revision);if(!changes.lockCategory(id))throw new CatalogFailure(CatalogFailure.Reason.NOT_FOUND);
            if(catalog.category(id,true).orElseThrow().revision()!=revision)throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);
            if(changes.categoryReferences(id)>0)throw new CatalogFailure(CatalogFailure.Reason.CATEGORY_IN_USE);
            changes.deleteCategory(id,revision);audit("CATEGORY_DELETED",actor,"CATEGORY",id,requestId);return null;});
    }
    public CatalogPage<Author> authors(UUID actor,int page,int size) {return reads.execute(tx -> {admin(actor);CatalogValidation.query(new CatalogQuery(null,null,null,null,page,size,null),true);return catalog.authors(page,size);});}
    public Author author(UUID actor,UUID id,Long revision,String name,String slug,String bio,String requestId) {
        return mutate(actor,() -> {String title=CatalogValidation.text(name,"name",160,true),biography=CatalogValidation.text(bio,"biography",4000,false);
            UUID target=id==null?UUID.randomUUID():id;
            String key=CatalogValidation.slug(slug==null||slug.isBlank()?CatalogValidation.slug(null,title,160)+"-"+target.toString().substring(0,8):slug,title,200);
            if(id==null)changes.createAuthor(target,title,key,biography);
            else {CatalogValidation.revision(revision==null?-1:revision);if(!changes.lockAuthor(id))throw new CatalogFailure(CatalogFailure.Reason.NOT_FOUND);if(!changes.updateAuthor(id,revision,title,key,biography))throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);}
            if(id!=null)changes.refreshAuthorDisplay(id);
            audit(id==null?"AUTHOR_CREATED":"AUTHOR_UPDATED",actor,"AUTHOR",target,requestId);return catalog.author(target).orElseThrow();});
    }
    public void deleteAuthor(UUID actor,UUID id,long revision,String requestId) {
        mutate(actor,() -> {CatalogValidation.revision(revision);if(!changes.lockAuthor(id))throw new CatalogFailure(CatalogFailure.Reason.NOT_FOUND);if(!changes.deleteAuthor(id,revision))throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);audit("AUTHOR_DELETED",actor,"AUTHOR",id,requestId);return null;});
    }

    public com.example.epub.core.model.catalog.EditorialModels.Editorial editorial(UUID actor,UUID id) {
        return reads.execute(tx->{admin(actor);var book=catalog.find(id,true).orElseThrow(()->new CatalogFailure(CatalogFailure.Reason.NOT_FOUND));return new com.example.epub.core.model.catalog.EditorialModels.Editorial(editorial.quote(id),editorial.characters(id),book.revision());});
    }
    public com.example.epub.core.model.catalog.EditorialModels.Editorial quote(UUID actor,UUID id,long revision,String quote,String requestId) {
        return mutate(actor,()->{lock(id,revision);editorial.quote(id,CatalogValidation.text(quote,"quote",500,false));changes.touchBook(id,revision);audit("BOOK_QUOTE_UPDATED",actor,"BOOK",id,requestId);return editorial(actor,id);});
    }
    public com.example.epub.core.model.catalog.EditorialModels.Editorial characters(UUID actor,UUID id,long revision,List<com.example.epub.core.model.catalog.EditorialModels.CharacterDraft> input,String requestId) {
        return mutate(actor,()->{lock(id,revision);
            if(input==null||input.size()>30)throw CatalogFailure.invalid("characters","At most 30 characters");
            var current=new HashMap<UUID,com.example.epub.core.model.catalog.EditorialModels.Character>();for(var c:editorial.characters(id))current.put(c.id(),c);
            var seen=new HashSet<UUID>();var result=new ArrayList<com.example.epub.core.model.catalog.EditorialModels.Character>();
            for(var c:input) {
                if(c==null)throw CatalogFailure.invalid("characters","Character is required");
                if(c.id()!=null&&(!current.containsKey(c.id())||!seen.add(c.id())))throw CatalogFailure.invalid("characters","Unknown or duplicate character ID for this book");
                String color=c.color()==null?"#3343db":c.color();if(!color.matches("#[0-9a-fA-F]{6}"))throw CatalogFailure.invalid("color","Use hex color");
                result.add(new com.example.epub.core.model.catalog.EditorialModels.Character(c.id()==null?UUID.randomUUID():c.id(),CatalogValidation.text(c.name(),"name",120,true),CatalogValidation.text(c.role(),"role",100,false),CatalogValidation.text(c.description(),"description",1000,false),CatalogValidation.text(c.initials(),"initials",8,false),color,result.size(),c.id()==null?0:current.get(c.id()).revision()+1));
            }
            editorial.characters(id,result);changes.touchBook(id,revision);audit("BOOK_CHARACTERS_UPDATED",actor,"BOOK",id,requestId);return editorial(actor,id);
        });
    }
}
