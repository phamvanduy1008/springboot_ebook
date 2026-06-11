package com.example.epub.core.model.epub;
import java.util.*;
public final class ReaderModels {
    private ReaderModels() { }
    public record Version(UUID bookId,UUID versionId,boolean privateBook,String tocSource) { }
    public record Asset(UUID id,String href,String url,String mediaType,long sizeBytes,String sha256) { }
    public record Spine(UUID id,int index,String title,String href,String resourceUrl,boolean linear,int rawUtf16Length) { }
    public record Toc(UUID id,UUID parentId,int ordinal,String label,UUID spineItemId,String fragment,boolean chapter) { }
    public record Chapter(UUID id,String title,String html,String baseUrl) { }
    public record Manifest(UUID bookId,UUID versionId,Asset reader,Asset cover,List<Spine> spine,List<Toc> toc,List<Asset> resources,boolean privateBook,String tocSource) { }
    /** Internal handle, never serialized. */
    public record Delivery(Version version,EpubModels.ObjectRow object) { }
}
