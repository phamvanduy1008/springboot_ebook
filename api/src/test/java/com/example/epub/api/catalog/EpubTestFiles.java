package com.example.epub.api.catalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
final class EpubTestFiles {
    private EpubTestFiles() { }
    static byte[] book() throws IOException {
        var files=new LinkedHashMap<String,String>();files.put("mimetype","application/epub+zip");
        files.put("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>");
        files.put("book.opf","<package xmlns='http://www.idpf.org/2007/opf' version='3.0'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Local EPUB</dc:title></metadata><manifest><item id='c' href='chapter.xhtml' media-type='application/xhtml+xml'/><item id='n' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/></manifest><spine><itemref idref='c'/></spine></package>");
        files.put("chapter.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Local chapter</title></head><body><h1 id='one'>Chương một</h1><p>Xin chào 😀</p><h2 id='two'>Phần hai</h2><script>alert('bad')</script></body></html>");
        files.put("nav.xhtml","<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>TOC</title></head><body><nav epub:type='toc'><ol><li><a href='chapter.xhtml#one'>Chương một</a><ol><li><a href='chapter.xhtml#two'>Phần hai</a></li></ol></li></ol></nav></body></html>");
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {for(var file:files.entrySet()) {byte[] bytes=file.getValue().getBytes(StandardCharsets.UTF_8);var entry=new ZipEntry(file.getKey());entry.setTime(315532800000L);if(file.getKey().equals("mimetype")) {var crc=new CRC32();crc.update(bytes);entry.setMethod(ZipEntry.STORED);entry.setSize(bytes.length);entry.setCrc(crc.getValue());}zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry();}}return output.toByteArray();
    }
}
