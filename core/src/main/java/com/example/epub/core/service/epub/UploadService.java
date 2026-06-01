package com.example.epub.core.service.epub;
import com.example.epub.core.model.epub.EpubModels.*;
import com.example.epub.core.repository.epub.UploadRepository;
import com.example.epub.core.integration.storage.*;
import com.example.epub.core.integration.epub.EpubExtractor;
import com.example.epub.core.integration.epub.EpubEnvelope;
import com.example.epub.core.service.jobs.JobService;
import com.example.epub.core.service.identity.*;
import com.example.epub.core.service.catalog.CatalogValidation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionTemplate;
import static com.example.epub.core.service.epub.EpubFailure.Reason.*;

public class UploadService {
    public static final int MAX_UPLOAD_BYTES=EpubExtractor.MAX_SOURCE;
    private final UploadRepository uploads;private final ObjectStorage storage;private final JobService jobs;private final AccountService accounts;private final TransactionTemplate tx;
    private final long privateQuota,adminQuota;
    public UploadService(UploadRepository uploads,ObjectStorage storage,JobService jobs,AccountService accounts,TransactionTemplate tx,long privateQuota,long adminQuota) {
        this.uploads=uploads;this.storage=storage;this.jobs=jobs;this.accounts=accounts;this.tx=tx;this.privateQuota=privateQuota;this.adminQuota=adminQuota;
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(120);
    }
    private com.example.epub.core.model.identity.Account actor(Actor actor) {
        var user=accounts.byId(actor.id()).orElse(null);if(!AccessPolicy.active(user)||!Objects.equals(user.passwordChangedAt(),actor.credentialEpoch()))throw new AccessPolicy.Denied(false);return user;
    }
    private void authorize(Actor actor,UUID book,boolean publicUpload) {
        var user=actor(actor);if(publicUpload)AccessPolicy.requireAdministrator(user);else AccessPolicy.requireVerified(user);
        if(book!=null) {
            var row=uploads.book(book,false).orElseThrow(()->new EpubFailure(NOT_FOUND));
            if(row.status().equals("DELETED")||publicUpload&&!row.visibility().equals("PUBLIC")||!publicUpload&&(!row.visibility().equals("PRIVATE")||!actor.id().equals(row.owner())))throw new EpubFailure(NOT_FOUND);
        }
    }
    public Accepted upload(Actor actor,UUID book,boolean publicUpload,long revision,UUID key,String title,String author,String rights,InputStream input) {
        if(key==null||revision<0)throw new EpubFailure(INVALID_INPUT);
        title=CatalogValidation.text(title,"title",300,book==null);author=CatalogValidation.text(author,"author",500,book==null);rights=CatalogValidation.text(rights,"rightsNote",4000,false);
        authorize(actor,book,publicUpload); // reject unauthorized uploads before consuming bytes
        ObjectStorage.ObjectInfo staged=null,source=null;AtomicBoolean keep=new AtomicBoolean(false);
        try {
            staged=storage.stage(input,EpubExtractor.MAX_SOURCE);signature(staged);source=storage.promote(staged);
            var object=source;String fingerprint=hash(Objects.toString(book,"new-private"),Boolean.toString(publicUpload),Long.toString(revision),title,author,rights,source.sha256());
            final String finalTitle=title,finalAuthor=author,finalRights=rights;
            return tx.execute(status->{
                if(!uploads.lockUser(actor.id()))throw new AccessPolicy.Denied(false);authorize(actor,book,publicUpload);
                var prior=uploads.receipt(actor.id(),key);
                if(prior.isPresent()) {if(!prior.get().fingerprint().equals(fingerprint))throw new EpubFailure(IDEMPOTENCY_CONFLICT);return prior.get().result();}
                UUID target=book==null?uploads.privateBook(actor.id(),finalTitle,finalAuthor):book;
                var row=uploads.book(target,true).orElseThrow(()->new EpubFailure(NOT_FOUND));
                if(row.status().equals("DELETED"))throw new EpubFailure(NOT_FOUND);
                if(row.revision()!=revision)throw new EpubFailure(REVISION_CONFLICT);
                if(!publicUpload&&uploads.processing(actor.id())>=3)throw new EpubFailure(QUOTA_EXCEEDED);
                UUID version=uploads.version(target,finalRights);
                if(!uploads.reserve(actor.id(),key,version,object.size(),publicUpload?adminQuota:privateQuota))throw new EpubFailure(QUOTA_EXCEEDED);
                uploads.source(target,version,UUID.randomUUID(),object.key(),object.sha256(),object.size());
                UUID job=jobs.enqueue(publicUpload?null:actor.id(),target,version,"INGEST","ingest:"+version);
                var result=new Accepted(target,version,job,uploads.touch(target),"PROCESSING");uploads.receipt(actor.id(),key,fingerprint,result);
                // Called after commit as well as rollback: do not delete a committed source on an HTTP disconnect.
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {keep.set(true);}
                });
                return result;
            });
        } catch(ObjectStorage.LimitExceeded tooLarge) {throw new EpubFailure(TOO_LARGE);}
        catch(IOException failure) {throw new EpubFailure(STORAGE_UNAVAILABLE);}
        finally {if(!keep.get()) {quietDelete(source==null?null:source.key());quietDelete(staged==null?null:staged.key());}}
    }
    private void quietDelete(String key) {if(key!=null)try {storage.delete(key);}catch(IOException ignored) { /* orphan sweeper retries later */ }}
    private void signature(ObjectStorage.ObjectInfo staged) {
        try {EpubEnvelope.validate(()->storage.open(staged.key()),staged.size());}catch(IOException invalid) {throw new EpubFailure(UNSUPPORTED);}
    }
    private static String hash(String... values) {
        try {var digest=MessageDigest.getInstance("SHA-256");for(String value:values) {byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);}return HexFormat.of().formatHex(digest.digest());}catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
