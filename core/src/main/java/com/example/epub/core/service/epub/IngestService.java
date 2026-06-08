package com.example.epub.core.service.epub;
import com.example.epub.core.integration.epub.EpubExtractor;
import com.example.epub.core.integration.storage.ObjectStorage;
import com.example.epub.core.model.epub.EpubModels.*;
import com.example.epub.core.model.jobs.JobModels.Lease;
import com.example.epub.core.repository.epub.IngestRepository;
import com.example.epub.core.service.jobs.JobHandler;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.transaction.support.*;

public class IngestService implements JobHandler {
    private final IngestRepository ingest;private final ObjectStorage storage;private final TransactionTemplate tx;private final EpubExtractor extractor=new EpubExtractor();
    private final AtomicLong cleanupAfter=new AtomicLong();
    public IngestService(IngestRepository ingest,ObjectStorage storage,TransactionTemplate tx) {this.ingest=ingest;this.storage=storage;this.tx=tx;tx.setTimeout(120);}
    @Override public String type() {return "INGEST";}
    public boolean acceptedSource(UUID book,String hash) {return ingest.acceptedSource(book,hash,EpubExtractor.VERSION,EpubExtractor.SANITIZER);}
    @Override public Runnable prepare(Lease job) throws Exception {
        var source=ingest.source(job.bookId(),job.versionId()).orElseThrow(()->new Rejected("EPUB_SOURCE_UNAVAILABLE"));
        var info=storage.stat(source.object().key());if(!info.sha256().equals(source.object().hash())||info.size()!=source.object().size())throw new Rejected("EPUB_SOURCE_INTEGRITY_FAILED");
        EpubExtractor.Result result;
        try(var input=storage.open(info.key())) {byte[] bytes=input.readNBytes(EpubExtractor.MAX_SOURCE+1);try {result=extractor.extract(bytes);}catch(EpubExtractor.Invalid failure) {throw new Rejected(failure.code());}}
        var objects=new ArrayList<ObjectRow>();var resources=new LinkedHashMap<String,ObjectRow>();var raw=new ArrayList<ObjectRow>();
        try {
            ObjectRow reader=store(result.reader(),"application/epub+zip","READER_EPUB");objects.add(reader);
            for(var resource:result.resources()) {var object=store(resource.bytes(),resource.mediaType(),resource.href().equals(result.coverHref())?"COVER":"RESOURCE");objects.add(object);resources.put(resource.href(),object);}
            for(var spine:result.spine()) {var text=store(spine.rawText().getBytes(StandardCharsets.UTF_8),"text/plain; charset=utf-8","RAW_TEXT");objects.add(text);raw.add(text);}
            return new Prepared(job,source,result,List.copyOf(objects),resources,raw,reader);
        } catch(Exception failure) {discard(objects);throw failure;}
    }
    private ObjectRow store(byte[] bytes,String media,String kind) throws IOException {
        ObjectStorage.ObjectInfo staged=null,promoted=null;
        try {staged=storage.stage(new ByteArrayInputStream(bytes),Math.max(1,bytes.length));promoted=storage.promote(staged);if(!storage.stat(promoted.key()).equals(promoted))throw new IOException("Artifact integrity failure");return new ObjectRow(UUID.randomUUID(),promoted.key(),promoted.sha256(),promoted.size(),media,kind);}
        catch(IOException failure) {try {if(staged!=null)storage.delete(staged.key());if(promoted!=null)storage.delete(promoted.key());}catch(IOException ignored) { }throw failure;}
    }
    private final class Prepared implements Runnable,AutoCloseable {
        private final Lease job;private final Source source;private final EpubExtractor.Result result;private final List<ObjectRow> objects,raw;private final Map<String,ObjectRow> resources;private final ObjectRow reader;
        Prepared(Lease job,Source source,EpubExtractor.Result result,List<ObjectRow> objects,Map<String,ObjectRow> resources,List<ObjectRow> raw,ObjectRow reader) {this.job=job;this.source=source;this.result=result;this.objects=objects;this.resources=resources;this.raw=raw;this.reader=reader;}
        @Override public void run() {
            UUID book=job.bookId(),version=job.versionId();if(!ingest.lockProcessing(book,version))throw new Rejected("EPUB_VERSION_UNAVAILABLE");
            // All objects have been promoted and verified before this fenced result transaction.
            for(var object:objects)ingest.object(book,version,object);
            for(var resource:resources.entrySet())ingest.resource(book,version,resource.getKey(),resource.getValue().id());
            var spineIds=new ArrayList<UUID>();for(int i=0;i<result.spine().size();i++) {var item=result.spine().get(i);UUID id=UUID.randomUUID();spineIds.add(id);ingest.spine(book,version,id,i,item.href(),item.title(),item.linear(),raw.get(i).id(),item.rawText().length());}
            var tocIds=new ArrayList<UUID>();for(var entry:result.toc()) {UUID id=UUID.randomUUID();tocIds.add(id);ingest.toc(book,version,id,entry.parent()<0?null:tocIds.get(entry.parent()),entry.ordinal(),entry.label(),entry.spineIndex()==null?null:spineIds.get(entry.spineIndex()),entry.fragment(),entry.chapter());}
            long total=source.object().size();for(var object:objects)total=Math.addExact(total,object.size());if(!ingest.quota(version,total))throw new Rejected("EPUB_QUOTA_EXCEEDED");
            var cover=resources.get(result.coverHref());Integer count=result.tocSource().equals("FALLBACK")?null:(int)result.toc().stream().filter(EpubExtractor.Toc::chapter).count();ingest.ready(book,version,reader.id(),cover==null?null:cover.id(),EpubExtractor.VERSION,EpubExtractor.SANITIZER,count,result.tocSource());
        }
        @Override public void close() {discard(objects);}
    }
    private void discard(List<ObjectRow> objects) {
        try {var keep=ingest.referenced(objects.stream().map(ObjectRow::key).toList());for(var object:objects)if(!keep.contains(object.key()))try {storage.delete(object.key());}catch(IOException ignored) { }}catch(RuntimeException unavailable) { /* TTL cleanup after DB recovery */ }
    }
    @Override public void failed(Lease job) {release(job.versionId());}
    private void release(UUID version) {
        var keys=ingest.failTerminal(version);if(keys.isEmpty())return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {for(String key:keys)try {storage.delete(key);}catch(IOException ignored) { /* orphan maintenance retries */ }}
        });
    }
    @Override public void maintain() {
        for(UUID version:ingest.terminalVersions())tx.executeWithoutResult(status->release(version));
        long now=System.currentTimeMillis(),next=cleanupAfter.get();if(now<next||!cleanupAfter.compareAndSet(next,now+3600000))return;
        Instant cutoff=Instant.now().minus(Duration.ofHours(48));
        try {storage.purgeStaging(cutoff);try(var stream=storage.objectsOlderThan(cutoff)) {var iterator=stream.iterator();while(iterator.hasNext()) {var batch=new ArrayList<String>();while(iterator.hasNext()&&batch.size()<500)batch.add(iterator.next());var keep=ingest.referenced(batch);for(String key:batch)if(!keep.contains(key))storage.delete(key);}}}catch(IOException|RuntimeException unavailable) { /* retry next maintenance cycle; no paths/errors exposed */ }
    }
}
