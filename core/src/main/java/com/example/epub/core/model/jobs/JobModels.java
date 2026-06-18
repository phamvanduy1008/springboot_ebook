package com.example.epub.core.model.jobs;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public final class JobModels {
    private JobModels() { }
    public record Lease(UUID id,UUID token,UUID ownerId,UUID bookId,UUID versionId,String type,int attempts) { }
    public record Status(UUID id,UUID bookId,UUID versionId,String type,String status,int attempts,int maxAttempts,String errorCode,Instant updatedAt) { }
    public record Page(List<Status> items,int page,int size,long total) { }
}
