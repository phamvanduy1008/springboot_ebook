package com.example.epub.core.integration.storage;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.Channels;
import java.security.*;
import java.time.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Local, private, immutable objects; a partial upload never receives an object key. */
public final class LocalObjectStorage implements ObjectStorage {
    private final Path root;
    public LocalObjectStorage(Path directory) throws IOException {
        root=directory.toAbsolutePath().normalize();
        checkedDirectories(root);
        checkedDirectories(root.resolve("staging"));
        checkedDirectories(root.resolve("objects"));
    }
    private static void checkedDirectories(Path directory) throws IOException {
        Path current=directory.getRoot();
        for(Path part:directory) {
            current=current.resolve(part);
            if(!Files.exists(current,NOFOLLOW_LINKS)) {
                try {Files.createDirectory(current);} catch(FileAlreadyExistsException race) { /* inspect below */ }
            }
            if(Files.isSymbolicLink(current)||!Files.isDirectory(current,NOFOLLOW_LINKS)
                    ||!current.toRealPath().equals(current))throw new IOException("Unsafe storage directory");
        }
    }
    private Path path(String key) throws IOException {
        if(key==null||!key.matches("(staging|objects)/[0-9a-f]{32}"))throw new IOException("Invalid storage identity");
        checkedDirectories(root.resolve(key.substring(0,key.indexOf('/'))));
        Path path=root.resolve(key);
        if(Files.isSymbolicLink(path))throw new IOException("Unsafe storage object");
        return path;
    }
    private static MessageDigest digest() {
        try {return MessageDigest.getInstance("SHA-256");} catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static String identity(String namespace) {return namespace+"/"+UUID.randomUUID().toString().replace("-","");}
    @Override public ObjectInfo stage(InputStream input,long maximumBytes) throws IOException {
        if(maximumBytes<1)throw new IllegalArgumentException("Positive byte limit required");
        String key=identity("staging");Path target=path(key);long size=0;MessageDigest hash=digest();boolean complete=false;
        try {
            try(var channel=Files.newByteChannel(target,Set.of(CREATE_NEW,WRITE,NOFOLLOW_LINKS));var output=Channels.newOutputStream(channel)) {
                byte[] buffer=new byte[65536];int count;
                while((count=input.read(buffer))!=-1) {
                    if(count==0)continue;
                    if(count>maximumBytes-size)throw new LimitExceeded();
                    output.write(buffer,0,count);hash.update(buffer,0,count);size+=count;
                }
            }
            complete=true;return new ObjectInfo(key,HexFormat.of().formatHex(hash.digest()),size);
        } finally {if(!complete)Files.deleteIfExists(target);}
    }
    @Override public ObjectInfo promote(ObjectInfo staged) throws IOException {
        if(staged==null||!staged.key().startsWith("staging/"))throw new IOException("Staged object required");
        ObjectInfo actual=stat(staged.key());
        if(!actual.equals(staged))throw new IOException("Staging integrity check failed");
        String key=identity("objects");
        Files.setLastModifiedTime(path(staged.key()),java.nio.file.attribute.FileTime.from(Instant.now()));
        // No copy fallback: storage/staging must share a filesystem supporting atomic rename.
        Files.move(path(staged.key()),path(key),StandardCopyOption.ATOMIC_MOVE);
        return new ObjectInfo(key,actual.sha256(),actual.size());
    }
    @Override public InputStream open(String key) throws IOException {
        return Channels.newInputStream(Files.newByteChannel(path(key),Set.of(READ,NOFOLLOW_LINKS)));
    }
    @Override public ObjectInfo stat(String key) throws IOException {
        MessageDigest hash=digest();long size=0;
        try(InputStream stream=open(key)) {byte[] buffer=new byte[65536];int count;while((count=stream.read(buffer))!=-1) {hash.update(buffer,0,count);size+=count;}}
        return new ObjectInfo(key,HexFormat.of().formatHex(hash.digest()),size);
    }
    @Override public void delete(String key) throws IOException {Files.deleteIfExists(path(key));}
    @Override public int purgeStaging(Instant olderThan) throws IOException {
        int removed=0;checkedDirectories(root.resolve("staging"));
        try(var files=Files.newDirectoryStream(root.resolve("staging"))) {
            for(Path file:files) {
                if(!file.getFileName().toString().matches("[0-9a-f]{32}")||Files.isSymbolicLink(file)||!Files.isRegularFile(file,NOFOLLOW_LINKS))continue;
                if(Files.getLastModifiedTime(file,NOFOLLOW_LINKS).toInstant().isBefore(olderThan)) {delete("staging/"+file.getFileName());removed++;}
            }
        }
        return removed;
    }
    @Override public java.util.stream.Stream<String> objectsOlderThan(Instant olderThan) throws IOException {
        checkedDirectories(root.resolve("objects"));
        return Files.list(root.resolve("objects")).filter(file->{
            if(!file.getFileName().toString().matches("[0-9a-f]{32}")||Files.isSymbolicLink(file)||!Files.isRegularFile(file,NOFOLLOW_LINKS))return false;
            try {return Files.getLastModifiedTime(file,NOFOLLOW_LINKS).toInstant().isBefore(olderThan);}catch(IOException unavailable) {return false;}
        }).map(file->"objects/"+file.getFileName());
    }
}
