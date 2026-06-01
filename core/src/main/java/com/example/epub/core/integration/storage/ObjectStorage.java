package com.example.epub.core.integration.storage;

import java.io.*;
import java.time.*;

/** Private object store. Keys are server identities and must never be accepted from HTTP clients. */
public interface ObjectStorage {
    final class LimitExceeded extends IOException { public LimitExceeded() {super("Upload exceeds byte limit");} }
    record ObjectInfo(String key, String sha256, long size) { }
    ObjectInfo stage(InputStream input, long maximumBytes) throws IOException;
    ObjectInfo promote(ObjectInfo staged) throws IOException;
    InputStream open(String key) throws IOException;
    ObjectInfo stat(String key) throws IOException;
    void delete(String key) throws IOException;
    int purgeStaging(Instant olderThan) throws IOException;
    /** Caller closes the lazy stream. Only server keys are returned; never expose them through HTTP. */
    java.util.stream.Stream<String> objectsOlderThan(Instant olderThan) throws IOException;
}
