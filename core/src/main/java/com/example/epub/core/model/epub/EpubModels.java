package com.example.epub.core.model.epub;
import java.time.Instant;
import java.util.UUID;
public final class EpubModels {
    private EpubModels() { }
    public record Actor(UUID id,Instant credentialEpoch) { }
    public record Accepted(UUID bookId,UUID versionId,UUID jobId,long revision,String state) { }
    public record Book(UUID id,UUID owner,String visibility,String status,long revision) { }
    public record Receipt(String fingerprint,Accepted result) { }
    public record ObjectRow(UUID id,String key,String hash,long size,String media,String kind) { }
}
