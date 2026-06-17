package com.example.epub.core.service.epub;

import com.example.epub.core.integration.storage.ObjectStorage;
import com.example.epub.core.model.epub.EpubModels.Actor;
import com.example.epub.core.model.epub.ReaderModels.*;
import com.example.epub.core.repository.epub.ReaderRepository;
import com.example.epub.core.service.identity.AccessPolicy;
import com.example.epub.core.service.identity.AccountService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.epub.core.service.epub.EpubFailure.Reason.*;

/** Read-only access to sanitized READY artifacts of public catalog books for active administrators. */
public class AdminPreviewService {
    private final ReaderRepository reader;
    private final ObjectStorage storage;
    private final AccountService accounts;
    private final TransactionTemplate reads;

    public AdminPreviewService(ReaderRepository reader,ObjectStorage storage,AccountService accounts,PlatformTransactionManager manager) {
        this.reader=reader;this.storage=storage;this.accounts=accounts;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);reads.setTimeout(30);
    }
    private Version authorize(Actor actor,UUID book,UUID version) {
        if(actor==null)throw new AccessPolicy.Denied(false);
        var account=accounts.byId(actor.id()).orElse(null);
        if(account==null || !Objects.equals(actor.credentialEpoch(),account.passwordChangedAt()))throw new AccessPolicy.Denied(false);
        AccessPolicy.requireAdministrator(account);
        return reader.adminPreviewVersion(book,version).orElseThrow(()->new EpubFailure(NOT_FOUND));
    }
    private static String base(Version version) {
        return "/api/v1/admin/books/"+version.bookId()+"/versions/"+version.versionId()+"/preview";
    }
    public Manifest manifest(Actor actor,UUID book,UUID requested) {
        return reads.execute(status->{
            var version=authorize(actor,book,requested);String base=base(version);
            var object=reader.reader(version.versionId(),false).orElseThrow(()->new EpubFailure(STORAGE_UNAVAILABLE));
            var artifact=new Asset(object.id(),"reader.epub",base+"/reader.epub",object.media(),object.size(),object.hash());
            var cover=reader.reader(version.versionId(),true).map(value->new Asset(value.id(),"cover",base+"/cover",value.media(),value.size(),value.hash())).orElse(null);
            var spine=reader.spine(version).stream().map(item->new Spine(item.id(),item.index(),item.title(),item.href(),base+"/resources/"+item.href().substring(5),item.linear(),item.rawUtf16Length())).toList();
            var resources=reader.resources(version).stream().map(item->new Asset(item.id(),item.href(),base+"/resources/"+item.href().substring(5),item.mediaType(),item.sizeBytes(),item.sha256())).toList();
            return new Manifest(book,version.versionId(),artifact,cover,spine,reader.toc(version.versionId()),resources,false,version.tocSource());
        });
    }
    public Delivery file(Actor actor,UUID book,UUID requested,String name,boolean cover) {
        return reads.execute(status->{
            var version=authorize(actor,book,requested);
            var object=cover?reader.reader(version.versionId(),true).orElseThrow(()->new EpubFailure(NOT_FOUND)):
                name==null?reader.reader(version.versionId(),false).orElseThrow(()->new EpubFailure(NOT_FOUND)):
                name.matches("r[0-9]+\\.(xhtml|css|jpg|png|gif|ttf|otf|woff|woff2)")?
                    reader.resource(version.versionId(),"EPUB/"+name).orElseThrow(()->new EpubFailure(NOT_FOUND)):
                    null;
            if(object==null)throw new EpubFailure(NOT_FOUND);
            return new Delivery(version,object);
        });
    }
    public void verify(Delivery file) {
        try {var object=file.object();var actual=storage.stat(object.key());if(actual.size()!=object.size()||!actual.sha256().equals(object.hash()))throw new IOException("Artifact integrity failure");}
        catch(IOException unavailable) {throw new EpubFailure(STORAGE_UNAVAILABLE);}
    }
    public InputStream open(Delivery file) throws IOException {return storage.open(file.object().key());}
    public Chapter chapter(Actor actor,UUID book,UUID requested,UUID chapterId) {
        var manifest=manifest(actor,book,requested);
        var entry=manifest.toc().stream().filter(item->item.id().equals(chapterId)).findFirst().orElseThrow(()->new EpubFailure(NOT_FOUND));
        var spine=manifest.spine().stream().filter(item->item.id().equals(entry.spineItemId())).findFirst().orElseThrow(()->new EpubFailure(NOT_FOUND));
        var file=file(actor,book,requested,spine.href().substring(5),false);verify(file);
        String end=manifest.toc().stream().filter(item->item.ordinal()>entry.ordinal()&&Objects.equals(item.spineItemId(),entry.spineItemId())&&item.fragment()!=null&&!Objects.equals(item.fragment(),entry.fragment())).map(Toc::fragment).findFirst().orElse(null);
        try(var input=open(file)) {
            var bytes=input.readNBytes(16*1024*1024+1);if(bytes.length>16*1024*1024)throw new IOException("Reader resource too large");
            return new Chapter(entry.id(),entry.label(),com.example.epub.core.integration.epub.ChapterDocument.slice(bytes,entry.fragment(),end),base(file.version())+"/resources/");
        } catch(IOException unavailable) {throw new EpubFailure(STORAGE_UNAVAILABLE);}
    }
}
