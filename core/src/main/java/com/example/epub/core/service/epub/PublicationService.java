package com.example.epub.core.service.epub;
import com.example.epub.core.model.epub.EpubModels.Actor;
import com.example.epub.core.model.epub.PublicationModels.State;
import com.example.epub.core.repository.epub.PublicationRepository;
import com.example.epub.core.integration.storage.ObjectStorage;
import com.example.epub.core.service.identity.*;
import java.io.IOException;
import java.util.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.epub.core.service.epub.EpubFailure.Reason.*;
public class PublicationService {
    private final PublicationRepository publication;private final ObjectStorage storage;private final AccountService accounts;private final TransactionTemplate tx;
    public PublicationService(PublicationRepository publication,ObjectStorage storage,AccountService accounts,TransactionTemplate tx) {this.publication=publication;this.storage=storage;this.accounts=accounts;this.tx=tx;tx.setTimeout(120);}
    public State change(Actor actor,UUID book,UUID version,long revision,UUID key,boolean publish,String requestId) {
        if(key==null||revision<0||publish&&version==null||!publish&&version!=null)throw new EpubFailure(INVALID_INPUT);
        return tx.execute(status->{
            if(!publication.lockAdmin(actor.id()))throw new AccessPolicy.Denied(false);
            var user=accounts.byId(actor.id()).orElse(null);AccessPolicy.requireAdministrator(user);if(!Objects.equals(actor.credentialEpoch(),user.passwordChangedAt()))throw new AccessPolicy.Denied(false);
            var current=publication.lockBook(book).orElseThrow(()->new EpubFailure(NOT_FOUND));String action=publish?"PUBLISH":"UNPUBLISH";
            var prior=publication.receipt(actor.id(),key);
            if(prior.isPresent()) {var previous=prior.get();if(!previous.bookId().equals(book)||!Objects.equals(previous.versionId(),version)||previous.baseRevision()!=revision||!previous.action().equals(action))throw new EpubFailure(IDEMPOTENCY_CONFLICT);return previous.result();}
            if(current.revision()!=revision)throw new EpubFailure(REVISION_CONFLICT);
            if(publish) {
                if(!publication.ready(book,version))throw new EpubFailure(INVALID_STATE);
                try {for(var object:publication.objects(version)) {var actual=storage.stat(object.key());if(actual.size()!=object.size()||!actual.sha256().equals(object.hash()))throw new IOException("Artifact integrity failure");}}
                catch(IOException unavailable) {throw new EpubFailure(STORAGE_UNAVAILABLE);}
            } else if(!Set.of("PUBLISHED","HIDDEN").contains(current.status()))throw new EpubFailure(INVALID_STATE);
            var result=publish?publication.publish(book,version,revision):publication.unpublish(book,revision);
            String safe=requestId!=null&&requestId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")?requestId:UUID.randomUUID().toString();publication.record(actor.id(),key,book,version,revision,action,result,safe);return result;
        });
    }
}
