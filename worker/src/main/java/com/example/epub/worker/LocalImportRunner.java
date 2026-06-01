package com.example.epub.worker;
import com.example.epub.core.service.epub.*;
import com.example.epub.core.service.identity.*;
import com.example.epub.core.service.catalog.CatalogAdminService;
import com.example.epub.core.model.epub.EpubModels.Actor;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Trusted host operator tool, opt-in only; no HTTP route and no directory discovery. */
@Component @Profile("local")
@ConditionalOnProperty(name="app.local-import.enabled",havingValue="true")
public class LocalImportRunner implements CommandLineRunner {
    private final Environment env;private final AccountService accounts;private final UploadService uploads;private final IngestService ingest;private final CatalogAdminService catalog;
    public LocalImportRunner(Environment env,AccountService accounts,UploadService uploads,IngestService ingest,CatalogAdminService catalog) {this.env=env;this.accounts=accounts;this.uploads=uploads;this.ingest=ingest;this.catalog=catalog;}
    static Path confinedSource(Path root,String name) throws IOException {
        Path relative=Path.of(name);if(relative.isAbsolute()||name.isBlank())throw new IOException("Relative file required");
        Path file=root.resolve(relative).normalize();
        if(!file.startsWith(root)||!file.toRealPath().equals(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub"))throw new IOException("Source outside import root or unsupported file");
        return file;
    }
    @Override public void run(String... args) throws Exception {
        Path root=Path.of(env.getRequiredProperty("app.local-import.root")).toRealPath();
        Path manifest=Path.of(env.getRequiredProperty("app.local-import.manifest")).toRealPath();
        if(Files.size(manifest)>1024*1024)throw new IllegalArgumentException("Import manifest exceeds 1 MiB");
        UUID actorId=UUID.fromString(env.getRequiredProperty("app.local-import.actor"));var user=accounts.byId(actorId).orElse(null);AccessPolicy.requireAdministrator(user);var actor=new Actor(actorId,user.passwordChangedAt());
        int limit=env.getProperty("app.local-import.limit",Integer.class,1);if(limit<1||limit>2000)throw new IllegalArgumentException("Import limit must be 1..2000");
        String rights=env.getProperty("app.local-import.rights-note","Local development import; cloud distribution deferred");
        int attempted=0,accepted=0,skipped=0,failed=0;
        try(var lines=Files.newBufferedReader(manifest,StandardCharsets.UTF_8)) {String line;
            while((line=lines.readLine())!=null&&attempted<limit) {
                if(line.isBlank()||line.startsWith("#"))continue;
                int separator=line.indexOf('\t');if(separator<1)throw new IllegalArgumentException("Manifest format is book UUID, TAB, relative EPUB path");
                UUID book=UUID.fromString(line.substring(0,separator));attempted++;
                try {
                    long revision=catalog.book(actorId,book).book().revision();
                    var source=confinedSource(root,line.substring(separator+1));
                    if(Files.size(source)>UploadService.MAX_UPLOAD_BYTES)throw new EpubFailure(EpubFailure.Reason.TOO_LARGE);
                    var hash=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(source)) {byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))!=-1)hash.update(buffer,0,count);}
                    if(ingest.acceptedSource(book,HexFormat.of().formatHex(hash.digest()))) {skipped++;System.out.println("LOCAL_IMPORT book="+book+" status=SKIPPED");continue;}
                    try(var input=Files.newInputStream(source)) {var result=uploads.upload(actor,book,true,revision,UUID.randomUUID(),"","",rights,input);accepted++;System.out.println("LOCAL_IMPORT book="+book+" version="+result.versionId()+" job="+result.jobId()+" status=QUEUED");}
                } catch(Exception failure) {failed++;String code=failure instanceof EpubFailure epub?epub.reason().name():failure instanceof AccessPolicy.Denied?"ACCESS_DENIED":"IMPORT_REJECTED";System.out.println("LOCAL_IMPORT book="+book+" status=FAILED code="+code);}
            }
        }
        System.out.println("LOCAL_IMPORT completed attempted="+attempted+" accepted="+accepted+" skipped="+skipped+" failed="+failed);
    }
}
