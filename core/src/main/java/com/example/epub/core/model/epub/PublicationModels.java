package com.example.epub.core.model.epub;
import java.util.UUID;
public final class PublicationModels {
    private PublicationModels() { }
    public record State(UUID bookId,UUID publishedVersionId,String status,long revision) { }
    public record Receipt(UUID bookId,UUID versionId,long baseRevision,String action,State result) { }
}
