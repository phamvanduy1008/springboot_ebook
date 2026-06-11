package com.example.epub.core.service.epub;
import com.example.epub.core.model.epub.ReaderModels.*;
import com.example.epub.core.model.epub.EpubModels.*;
import com.example.epub.core.repository.epub.ReaderRepository;
import com.example.epub.core.integration.storage.ObjectStorage;
import com.example.epub.core.service.identity.*;
import java.io.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.epub.core.service.epub.EpubFailure.Reason.*;
public class ReaderService {
    private final ReaderRepository reader;private final ObjectStorage storage;private final AccountService accounts;private final TransactionTemplate reads;
    public ReaderService(ReaderRepository reader,ObjectStorage storage,AccountService accounts,PlatformTransactionManager manager) {this.reader=reader;this.storage=storage;this.accounts=accounts;reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setTimeout(30);}
    private Version authorize(Actor actor,UUID book,UUID version) {
        if(actor!=null) {var user=accounts.byId(actor.id()).orElse(null);if(!AccessPolicy.active(user)||!Objects.equals(actor.credentialEpoch(),user.passwordChangedAt()))throw new AccessPolicy.Denied(false);}
        return reader.version(book,version,actor==null?null:actor.id()).orElseThrow(()->new EpubFailure(NOT_FOUND));
    }
    public Manifest manifest(Actor actor,UUID book,UUID requested) {
        return reads.execute(status->{
            var version=authorize(actor,book,requested);var object=reader.reader(version.versionId(),false).orElseThrow(()->new EpubFailure(STORAGE_UNAVAILABLE));
            String base="/api/v1/books/"+book+"/versions/"+version.versionId();
            var artifact=new Asset(object.id(),"reader.epub",base+"/reader.epub",object.media(),object.size(),object.hash());
            var cover=reader.reader(version.versionId(),true).map(value->new Asset(value.id(),"cover",base+"/cover",value.media(),value.size(),value.hash())).orElse(null);
            return new Manifest(book,version.versionId(),artifact,cover,reader.spine(version),reader.toc(version.versionId()),reader.resources(version),version.privateBook(),version.tocSource());
        });
    }
    public Delivery file(Actor actor,UUID book,UUID requested,String name,boolean cover) {
        return reads.execute(status->{
            var version=authorize(actor,book,requested);ObjectRow object;
            if(cover)object=reader.reader(version.versionId(),true).orElseThrow(()->new EpubFailure(NOT_FOUND));
            else if(name==null)object=reader.reader(version.versionId(),false).orElseThrow(()->new EpubFailure(NOT_FOUND));
            else {if(!name.matches("r[0-9]+\\.(xhtml|css|jpg|png|gif|ttf|otf|woff|woff2)"))throw new EpubFailure(NOT_FOUND);object=reader.resource(version.versionId(),"EPUB/"+name).orElseThrow(()->new EpubFailure(NOT_FOUND));}
            return new Delivery(version,object);
        });
    }
    public void verify(Delivery file) {
        try {var object=file.object();var actual=storage.stat(object.key());if(actual.size()!=object.size()||!actual.sha256().equals(object.hash()))throw new IOException("Artifact integrity failure");}catch(IOException unavailable) {throw new EpubFailure(STORAGE_UNAVAILABLE);}
    }
    public Chapter chapter(Actor actor,UUID book,UUID requested,UUID chapterId) {
        var manifest=manifest(actor,book,requested);
        var entry=manifest.toc().stream().filter(t->t.id().equals(chapterId)).findFirst().orElseThrow(()->new EpubFailure(NOT_FOUND));
        var spine=manifest.spine().stream().filter(s->s.id().equals(entry.spineItemId())).findFirst().orElseThrow(()->new EpubFailure(NOT_FOUND));
        var file=file(actor,book,manifest.versionId(),spine.href().substring(5),false);verify(file);
        String end=manifest.toc().stream().filter(t->t.ordinal()>entry.ordinal()&&Objects.equals(t.spineItemId(),entry.spineItemId())&&t.fragment()!=null&&!Objects.equals(t.fragment(),entry.fragment())).map(Toc::fragment).findFirst().orElse(null);
        try(var input=open(file)) {
            if(file.object().size()>16*1024*1024)throw new IOException("Reader resource too large");
            var bytes=input.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024)throw new IOException("Reader resource too large");
            return new Chapter(entry.id(),entry.label(),com.example.epub.core.integration.epub.ChapterDocument.slice(bytes,entry.fragment(),end),"/api/v1/books/"+book+"/versions/"+manifest.versionId()+"/resources/");
        } catch(IOException unavailable) {throw new EpubFailure(STORAGE_UNAVAILABLE);}
    }
    public InputStream open(Delivery file) throws IOException {return storage.open(file.object().key());}
}
