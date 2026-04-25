package com.example.epub.core.service.catalog;
import com.example.epub.core.model.catalog.*;
import java.text.Normalizer;
import java.util.*;
public final class CatalogValidation {
    private CatalogValidation() { }
    public static String text(String value,String field,int maximum,boolean required) {
        String result=value==null?"":value.strip();
        if((required && result.isBlank()) || result.codePointCount(0,result.length())>maximum || result.indexOf(0)>=0)
            throw CatalogFailure.invalid(field,"Invalid length or value");
        return result;
    }
    public static String slug(String supplied,String title,int max) {
        String value=supplied==null||supplied.isBlank()?Normalizer.normalize(title,Normalizer.Form.NFD).replaceAll("\\p{M}","")
            .replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$",""):supplied;
        if(value.isBlank())value="item";
        if(value.length()>max || !value.matches("[a-z0-9]+(-[a-z0-9]+)*"))throw CatalogFailure.invalid("slug","Use lowercase letters, digits and hyphens");
        return value;
    }
    public static void revision(long value) {if(value<0)throw CatalogFailure.invalid("revision","Revision is required");}
    public static CatalogQuery query(CatalogQuery value,boolean admin) {
        String q=text(value.q(),"q",200,false),sort=value.sort()==null?"updatedAt,desc":value.sort();
        if(!Set.of("updatedAt,desc","createdAt,desc","title,asc","title,desc","author,asc").contains(sort))throw CatalogFailure.invalid("sort","Unsupported sort");
        if(value.page()<0 || value.page()>1000000 || value.size()<1 || value.size()>100)throw CatalogFailure.invalid("page","Use a valid page and size 1..100");
        if(value.year()!=null && (value.year()<1 || value.year()>9999))throw CatalogFailure.invalid("year","Invalid year");
        if(value.status()!=null && (!admin || !Set.of("DRAFT","PUBLISHED","HIDDEN").contains(value.status())))throw CatalogFailure.invalid("status","Invalid publication status");
        return new CatalogQuery(q,value.category(),value.year(),sort,value.page(),value.size(),value.status());
    }
    private static List<UUID> ids(List<UUID> values,String field) {
        if(values==null)return List.of();
        if(values.size()>30 || values.stream().anyMatch(Objects::isNull) || new HashSet<>(values).size()!=values.size())throw CatalogFailure.invalid(field,"Use at most 30 distinct IDs");
        return List.copyOf(values);
    }
    public static BookDraft draft(BookDraft value) {
        String title=text(value.title(),"title",300,true),author=text(value.authorDisplay(),"authorDisplay",500,true);
        String state=value.serialStatus()==null?"UNKNOWN":value.serialStatus();
        if(!Set.of("UNKNOWN","ONGOING","COMPLETED","HIATUS").contains(state))throw CatalogFailure.invalid("serialStatus","Invalid serial status");
        String language=text(value.language(),"language",35,false);
        if(!language.isEmpty() && !language.matches("[a-zA-Z]{2,8}(-[a-zA-Z0-9]{1,8})*"))throw CatalogFailure.invalid("language","Invalid language tag");
        if(value.publicationYear()!=null && (value.publicationYear()<1 || value.publicationYear()>9999))throw CatalogFailure.invalid("publicationYear","Invalid year");
        var tags=value.tags()==null?List.<String>of():value.tags();
        if(tags.size()>30)throw CatalogFailure.invalid("tags","Use at most 30 tags");
        tags=tags.stream().map(tag -> text(tag,"tags",80,true)).distinct().toList();
        return new BookDraft(title,author,slug(value.slug(),title,350),text(value.summary(),"summary",2000,false),text(value.description(),"description",20000,false),
            language.isEmpty()?null:language,value.isTranslation(),value.publicationYear(),state,ids(value.authorIds(),"authorIds"),ids(value.categoryIds(),"categoryIds"),tags,text(value.rightsNote(),"rightsNote",4000,false));
    }
}
