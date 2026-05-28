package com.example.epub.core.epub;

import com.example.epub.core.integration.epub.EpubExtractor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Self-authored CC0 fixtures, generated in memory; no third-party books are distributed. */
public class EpubExtractorTest {
    public static Map<String,byte[]> fixture(boolean epub3) {
        var files=new LinkedHashMap<String,byte[]>();
        put(files,"mimetype","application/epub+zip");
        put(files,"META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OEBPS/book.opf'/></rootfiles></container>");
        put(files,"OEBPS/book.opf","""
            <package xmlns='http://www.idpf.org/2007/opf' version='%s'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:title>Truyện thử EPUB</dc:title></metadata>
            <manifest><item id='one' href='chương.xhtml' media-type='application/xhtml+xml'/><item id='two' href='end.xhtml' media-type='application/xhtml+xml'/>
            <item id='style' href='style.css' media-type='text/css'/><item id='pic' href='cover.png' media-type='image/png' properties='cover-image'/>
            <item id='font' href='test.ttf' media-type='font/ttf'/>
            <item id='nav' href='%s' media-type='%s' %s/></manifest><spine toc='nav'><itemref idref='one'/><itemref idref='two'/></spine></package>
            """.formatted(epub3?"3.0":"2.0",epub3?"nav.xhtml":"toc.ncx",epub3?"application/xhtml+xml":"application/x-dtbncx+xml",epub3?"properties='nav'":""));
        put(files,"OEBPS/chương.xhtml","""
            <html xmlns='http://www.w3.org/1999/xhtml'><head><title>Chapter one</title><link rel='stylesheet' href='style.css'/></head><body>
            <h1 id='mở-đầu'>Mở đầu</h1><p>Xin chào 😀</p><p class='fonttest'>A</p><img src='cover.png' alt='Bìa'/>
            <p style='height:900px'>Khoảng cách kiểm thử.</p>
            <h2 id='phần-hai'>Phần hai</h2><p>Đoạn thứ hai.</p><script>window.EPUB_ATTACK=true</script>
            <iframe src='https://invalid.example/'/><a href='javascript:alert(1)' onclick='alert(1)'>Bad link</a></body></html>
            """);
        put(files,"OEBPS/end.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>End</title></head><body><h1 id='end'>Kết thúc</h1><p>A</p></body></html>");
        put(files,"OEBPS/style.css","@font-face{font-family:EpubTestCC0;src:url('test.ttf')}body{color:#123456;font-size:18px}.fonttest{font-family:EpubTestCC0}p{background:url(https://invalid.example/tracker);text-align:justify}");
        files.put("OEBPS/test.ttf",Base64.getDecoder().decode("AAEAAAAKAIAAAwAgT1MvMkUAQ9EAAAEoAAAAYGNtYXAAdABcAAABkAAAADxnbHlmMZW57gAAAdQAAAAwaGVhZFlTpJwAAACsAAAANmhoZWEEdgHOAAAA5AAAACRobXR4AfQAAAAAAYgAAAAIbG9jYQAMACQAAAHMAAAACG1heHAABQAFAAABCAAAACBuYW1l7TJOTwAAAgQAAAGGcG9zdAAIACQAAAOMAAAAKAABAAAAAQAAHXKz3V8PPPUAAQPoAAAAAHwlsIAAAAAAfCWwgABQAAABpAK8AAAAAwACAAAAAAAAAAEAAAMg/zgAAAH0AAAAoAFUAAEAAAAAAAAAAAAAAAAAAAABAAEAAAADAAMAAQAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAwH0AZAABQAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAPz8/PwAAACAAQQMg/zgAAAMgAMgAAAAAAAAAAAAAAAAAAAAgAAAB9AAAAAAAAAAAAAIAAAADAAAAFAADAAEAAAAUAAQAKAAAAAYABAABAAIAIABB//8AAAAgAEH////h/8EAAQAAAAAAAAAAAAwADAAYAAEAUAAAAaQCvAACAAAzExNQqqoCvP1EAAEAUAAAAaQCvAACAAAzExNQqqoCvP1EAAAADACWAAEAAAAAAAEACwAAAAEAAAAAAAIABwALAAEAAAAAAAMADQASAAEAAAAAAAQAEwAfAAEAAAAAAAUACwAyAAEAAAAAAAYAEwA9AAMAAQQJAAEAFgBQAAMAAQQJAAIADgBmAAMAAQQJAAMAGgB0AAMAAQQJAAQAJgCOAAMAAQQJAAUAFgC0AAMAAQQJAAYAJgDKRXB1YlRlc3RDQzBSZWd1bGFyRXB1YlRlc3RDQzAtMUVwdWJUZXN0Q0MwIFJlZ3VsYXJWZXJzaW9uIDEuMEVwdWJUZXN0Q0MwLVJlZ3VsYXIARQBwAHUAYgBUAGUAcwB0AEMAQwAwAFIAZQBnAHUAbABhAHIARQBwAHUAYgBUAGUAcwB0AEMAQwAwAC0AMQBFAHAAdQBiAFQAZQBzAHQAQwBDADAAIABSAGUAZwB1AGwAYQByAFYAZQByAHMAaQBvAG4AIAAxAC4AMABFAHAAdQBiAFQAZQBzAHQAQwBDADAALQBSAGUAZwB1AGwAYQByAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAAAAwAk"));
        files.put("OEBPS/cover.png",Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4XcAAAAASUVORK5CYII="));
        if(epub3)put(files,"OEBPS/nav.xhtml","""
            <html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><title>Contents</title></head><body><nav epub:type='toc'><ol>
            <li><a href='ch%C6%B0%C6%A1ng.xhtml#m%E1%BB%9F-%C4%91%E1%BA%A7u'>Mở đầu</a><ol><li><a href='chương.xhtml#phần-hai'>Phần hai</a></li></ol></li>
            <li><a href='end.xhtml#end'>Kết thúc</a></li></ol></nav></body></html>
            """);
        else put(files,"OEBPS/toc.ncx","""
            <ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint id='a'><navLabel><text>Mở đầu</text></navLabel><content src='chương.xhtml#mở-đầu'/>
            <navPoint id='b'><navLabel><text>Phần hai</text></navLabel><content src='chương.xhtml#phần-hai'/></navPoint></navPoint>
            <navPoint id='c'><navLabel><text>Kết thúc</text></navLabel><content src='end.xhtml#end'/></navPoint></navMap></ncx>
            """);
        if(!epub3) {
            put(files,"OEBPS/chương.xhtml","<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.1//EN' 'http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd'>"+new String(files.get("OEBPS/chương.xhtml"),StandardCharsets.UTF_8));
            put(files,"OEBPS/toc.ncx","<!DOCTYPE ncx PUBLIC '-//NISO//DTD ncx 2005-1//EN' 'http://www.daisy.org/z3986/2005/ncx-2005-1.dtd'>"+new String(files.get("OEBPS/toc.ncx"),StandardCharsets.UTF_8));
        }
        return files;
    }
    public static void put(Map<String,byte[]> files,String key,String value) {files.put(key,value.getBytes(StandardCharsets.UTF_8));}
    public static byte[] zip(Map<String,byte[]> files) throws IOException {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipArchiveOutputStream(bytes)) {for(var file:files.entrySet()) {var entry=new ZipArchiveEntry(file.getKey());if(file.getKey().equals("mimetype")) {var crc=new java.util.zip.CRC32();crc.update(file.getValue());entry.setMethod(java.util.zip.ZipEntry.STORED);entry.setSize(file.getValue().length);entry.setCrc(crc.getValue());}zip.putArchiveEntry(entry);zip.write(file.getValue());zip.closeArchiveEntry();}}return bytes.toByteArray();
    }
    @Test void epub2And3PreserveNestedFragmentsUtf16AndDeterministicReaderArtifacts() throws Exception {
        for(boolean three:List.of(false,true)) {
            var parser=new EpubExtractor();byte[] source=zip(fixture(three));var result=parser.extract(source);
            assertEquals(2,result.spine().size());assertEquals(3,result.toc().size());assertEquals(0,result.toc().get(1).parent());assertEquals(0,result.toc().get(1).spineIndex());assertEquals("phần-hai",result.toc().get(1).fragment());
            assertTrue(result.spine().getFirst().rawText().contains("Xin chào 😀"));assertFalse(result.spine().getFirst().rawText().contains("EPUB_ATTACK"));
            assertArrayEquals(result.reader(),parser.extract(source).reader());assertFalse(Arrays.equals(source,result.reader()));
            var reopened=parser.extract(result.reader());assertEquals(result.spine().getFirst().rawText(),reopened.spine().getFirst().rawText());
            for(var resource:result.resources())if(resource.mediaType().contains("xhtml")||resource.mediaType().equals("text/css")) {String text=new String(resource.bytes(),StandardCharsets.UTF_8);assertFalse(text.contains("javascript:"));assertFalse(text.contains("onclick"));assertFalse(text.contains("https://invalid.example"));assertFalse(text.contains("<script"));}
            if(System.getProperty("epub.spike.output")!=null) {Path root=Path.of(System.getProperty("epub.spike.output"));Files.createDirectories(root);Files.write(root.resolve(three?"epub3-reader.epub":"epub2-reader.epub"),result.reader());Files.write(root.resolve(three?"epub3-source.epub":"epub2-source.epub"),source);}
        }
    }
    @Test void hostilePathsXmlLimitsDrmAndFixedLayoutFailClosed() throws Exception {
        var parser=new EpubExtractor();
        for(String bad:List.of("../escape","/absolute","OEBPS/../alias","C:/escape","OEBPS/%2e%2e/escape")) {var files=fixture(true);put(files,bad,"bad");assertThrows(EpubExtractor.Invalid.class,()->parser.extract(zip(files)));}
        for(String xml:List.of("<!DOCTYPE html [<!ENTITY x SYSTEM 'file:///private'>]><html>&x;</html>","<html>"+"<div>".repeat(70)+"x"+"</div>".repeat(70)+"</html>")) {var files=fixture(true);put(files,"OEBPS/chương.xhtml",xml);assertThrows(EpubExtractor.Invalid.class,()->parser.extract(zip(files)));}
        var drm=fixture(true);put(drm,"META-INF/encryption.xml","<encryption/>");assertEquals("EPUB_DRM_UNSUPPORTED",assertThrows(EpubExtractor.Invalid.class,()->parser.extract(zip(drm))).code());
        var fixed=fixture(true);put(fixed,"OEBPS/book.opf",new String(fixed.get("OEBPS/book.opf"),StandardCharsets.UTF_8).replace("<metadata ","<metadata ").replace("</metadata>","<meta property='rendition:layout'>pre-paginated</meta></metadata>"));assertEquals("EPUB_FIXED_LAYOUT_UNSUPPORTED",assertThrows(EpubExtractor.Invalid.class,()->parser.extract(zip(fixed))).code());
        var missing=fixture(true);put(missing,"OEBPS/nav.xhtml",new String(missing.get("OEBPS/nav.xhtml"),StandardCharsets.UTF_8).replace("#phần-hai","#missing"));assertThrows(EpubExtractor.Invalid.class,()->parser.extract(zip(missing)));
        var bomb=fixture(true);bomb.put("bomb.txt",new byte[2*1024*1024]);assertEquals("EPUB_LIMIT_EXCEEDED",assertThrows(EpubExtractor.Invalid.class,()->parser.extract(zip(bomb))).code());
    }
    @Test void duplicateZipNamesAndUnixSymlinksAreRejectedWithoutExtractingToDisk() throws Exception {
        for(boolean symlink:List.of(false,true)) {
            var bytes=new ByteArrayOutputStream();try(var zip=new ZipArchiveOutputStream(bytes)) {
                byte[] mime="application/epub+zip".getBytes(StandardCharsets.US_ASCII);var crc=new java.util.zip.CRC32();crc.update(mime);var first=new ZipArchiveEntry("mimetype");first.setMethod(java.util.zip.ZipEntry.STORED);first.setSize(mime.length);first.setCrc(crc.getValue());zip.putArchiveEntry(first);zip.write(mime);zip.closeArchiveEntry();
                for(int i=0;i<2;i++) {var entry=new ZipArchiveEntry(symlink?"link"+i:"duplicate");if(symlink)entry.setUnixMode(0120777);zip.putArchiveEntry(entry);zip.write("../private".getBytes(StandardCharsets.UTF_8));zip.closeArchiveEntry();}}
            assertThrows(EpubExtractor.Invalid.class,()->new EpubExtractor().extract(bytes.toByteArray()));
        }
    }
}
