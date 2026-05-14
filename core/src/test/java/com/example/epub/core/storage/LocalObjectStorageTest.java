package com.example.epub.core.storage;

import com.example.epub.core.integration.storage.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalObjectStorageTest {
    @TempDir Path directory;
    @Test void immutablePromotionSurvivesRestartAndChecksIntegrity() throws Exception {
        var storage=new LocalObjectStorage(directory.resolve("private"));byte[] bytes="EPUB dữ liệu".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var staged=storage.stage(new ByteArrayInputStream(bytes),100);
        assertTrue(staged.key().startsWith("staging/"));assertEquals(bytes.length,staged.size());
        assertThrows(IOException.class,()->storage.promote(new ObjectStorage.ObjectInfo(staged.key(),"0".repeat(64),staged.size())));
        var object=storage.promote(staged);assertTrue(object.key().startsWith("objects/"));
        assertThrows(IOException.class,()->storage.open(staged.key()));
        var restarted=new LocalObjectStorage(directory.resolve("private"));assertEquals(object,restarted.stat(object.key()));
        try(var input=restarted.open(object.key())) {assertArrayEquals(bytes,input.readAllBytes());}
        restarted.delete(object.key());restarted.delete(object.key());
    }
    @Test void partialWritesLimitsAndHostileKeysAreRejected() throws Exception {
        var storage=new LocalObjectStorage(directory);
        assertThrows(LocalObjectStorage.LimitExceeded.class,()->storage.stage(new ByteArrayInputStream(new byte[20]),10));
        assertThrows(IOException.class,()->storage.stage(new InputStream() {int read;public int read() throws IOException {if(read++>3)throw new IOException("broken");return 1;}},100));
        try(var staged=Files.list(directory.resolve("staging"))) {assertEquals(0,staged.count());}
        for(String key:List.of("../outside","objects/../secret","objects/%2e%2e","C:\\secret","objects/abc","/etc/passwd"))assertThrows(IOException.class,()->storage.open(key));
        var old=storage.stage(new ByteArrayInputStream(new byte[]{1}),10);
        Files.setLastModifiedTime(directory.resolve(old.key()),java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        var recent=storage.stage(new ByteArrayInputStream(new byte[]{2}),10);
        assertEquals(1,storage.purgeStaging(Instant.now().minus(Duration.ofDays(2))));assertEquals(recent,storage.stat(recent.key()));
    }
    @Test void symbolicLinksCannotBecomeObjectsOrRoots() throws Exception {
        var storage=new LocalObjectStorage(directory.resolve("store"));var outside=Files.writeString(directory.resolve("outside"),"private");
        Path link=directory.resolve("store/objects/"+"a".repeat(32));
        try {Files.createSymbolicLink(link,outside);} catch(FileSystemException unavailable) {
            // Windows without SeCreateSymbolicLinkPrivilege: no mocked security success.
            org.junit.jupiter.api.Assumptions.abort("OS does not permit symlink creation");return;
        }
        assertThrows(IOException.class,()->storage.open("objects/"+"a".repeat(32)));
        Files.createSymbolicLink(directory.resolve("linked-root"),directory.resolve("store"));
        assertThrows(IOException.class,()->new LocalObjectStorage(directory.resolve("linked-root")));
    }
}
