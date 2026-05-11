package com.example.epub.core.service.library;

import com.example.epub.core.model.library.LibraryModels.*;
import com.example.epub.core.repository.library.LibraryRepository;
import com.example.epub.core.service.catalog.CatalogFailure;
import com.example.epub.core.service.catalog.CatalogValidation;
import com.example.epub.core.service.identity.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public class LibraryService {
    private final LibraryRepository repository;
    private final AccountService accounts;
    private final Clock clock;
    private final TransactionTemplate writes,reads;
    public LibraryService(LibraryRepository repository,AccountService accounts,PlatformTransactionManager manager,Clock clock) {
        this.repository=repository;this.accounts=accounts;this.clock=clock;
        writes=new TransactionTemplate(manager);reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private void actor(Actor actor) {
        var account=accounts.byId(actor.id()).orElse(null);
        if(!AccessPolicy.active(account) || !account.passwordChangedAt().equals(actor.credentialEpoch()))throw new AccessPolicy.Denied(false);
    }
    private <T> T read(Actor actor,Supplier<T> action) {return reads.execute(tx->{actor(actor);return action.get();});}
    private <T> T write(Actor actor,Supplier<T> action) {return writes.execute(tx->{if(!repository.lockUser(actor.id()))throw new AccessPolicy.Denied(false);actor(actor);repository.cleanReceipts(actor.id(),clock.instant().minus(Duration.ofHours(24)));return action.get();});}
    public Page list(Actor actor,String kind,Query q) {
        if(q.page()<0 || q.page()>1000000 || q.size()<1 || q.size()>100)throw CatalogFailure.invalid("page","Use page >= 0 and size 1..100");
        if(q.shelf()!=null && !Set.of("all","want","reading","finished").contains(q.shelf()))throw CatalogFailure.invalid("shelf","Use want, reading or finished; private is an ownership filter");
        if(q.ownership()!=null && !Set.of("all","owned","public").contains(q.ownership()))throw CatalogFailure.invalid("ownership","Use all, owned or public");
        String sort=q.sort()==null?"recent":q.sort();
        if(!Set.of("recent","title","author").contains(sort))throw CatalogFailure.invalid("sort","Invalid sort");
        String search=CatalogValidation.text(q.q(),"q",200,false);
        return read(actor,()->repository.list(actor.id(),kind,new Query(q.shelf(),q.ownership(),search,sort,q.page(),q.size())));
    }
    public State state(Actor actor,UUID book) {return read(actor,()->{var metadata=repository.accessible(actor.id(),book).orElseThrow(()->new CatalogFailure(CatalogFailure.Reason.NOT_FOUND));return repository.state(actor.id(),book,metadata.visibility().equals("PRIVATE"));});}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private boolean replay(UUID user,UUID key,String fingerprint) {
        var stored=repository.fingerprint(user,key);
        if(stored.isEmpty())return false;
        if(!stored.get().equals(fingerprint))throw new CatalogFailure(CatalogFailure.Reason.IDEMPOTENCY_CONFLICT);
        return true;
    }
    public State mutate(Actor actor,UUID book,String action,String shelf,long revision,UUID key) {
        if(key==null || revision<0)throw CatalogFailure.invalid("revision","A revision and UUID Idempotency-Key are required");
        if(action.equals("save") && (shelf==null || !Set.of("want","reading","finished").contains(shelf)))throw CatalogFailure.invalid("shelf","Use want, reading or finished");
        if(!Set.of("save","unsave","favorite","unfavorite","view","unview").contains(action))throw CatalogFailure.invalid("action","Unsupported action");
        String fingerprint=hash(action+"|"+book+"|"+shelf+"|"+revision);
        return write(actor,()->{
            if(replay(actor.id(),key,fingerprint))return repository.replayState(actor.id(),key);
            var metadata=repository.accessible(actor.id(),book);
            boolean remove=Set.of("unsave","unfavorite","unview").contains(action);
            if(metadata.isEmpty() && (!remove || !repository.exists(actor.id(),book)))throw new CatalogFailure(CatalogFailure.Reason.NOT_FOUND);
            var old=repository.state(actor.id(),book,metadata.map(b->b.visibility().equals("PRIVATE")).orElse(false));
            if(old.revision()!=revision)throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);
            String nextShelf=action.equals("save")?shelf:action.equals("unsave")?null:old.shelf();
            boolean favorite=action.equals("favorite") || (!action.equals("unfavorite") && old.favorite());
            Instant viewed=action.equals("view")?clock.instant():action.equals("unview")?null:old.viewedAt();
            if(!Objects.equals(nextShelf,old.shelf()) || favorite!=old.favorite() || !Objects.equals(viewed,old.viewedAt()))repository.write(actor.id(),old,nextShelf,favorite,viewed,clock.instant());
            if(action.equals("view"))repository.trimHistory(actor.id());
            var result=repository.state(actor.id(),book,old.owned());repository.receipt(actor.id(),key,fingerprint,result);return result;
        });
    }
    public Settings settings(Actor actor) {return read(actor,()->repository.settings(actor.id()));}
    public Settings settings(Actor actor,Settings value,UUID key) {
        if(value==null || key==null || value.revision()<0 || value.theme()==null || !Set.of("light","dark","system").contains(value.theme()) || value.readerTheme()==null || !Set.of("light","dark","sepia").contains(value.readerTheme()) || value.readerFont()==null || !Set.of("serif","sans").contains(value.readerFont()) || value.fontSize()<14 || value.fontSize()>40 || !Double.isFinite(value.lineHeight()) || value.lineHeight()<1.2 || value.lineHeight()>2.4 || Math.abs(value.lineHeight()*100-Math.rint(value.lineHeight()*100))>0.00001)throw CatalogFailure.invalid("settings","Only supported appearance preferences and revision are accepted");
        String fingerprint=hash("settings|"+value.toString());
        return write(actor,()->{if(replay(actor.id(),key,fingerprint))return repository.replaySettings(actor.id(),key);var old=repository.settings(actor.id());if(old.revision()!=value.revision())throw new CatalogFailure(CatalogFailure.Reason.REVISION_CONFLICT);var result=repository.settings(actor.id(),value);repository.receipt(actor.id(),key,fingerprint,result);return result;});
    }
}
