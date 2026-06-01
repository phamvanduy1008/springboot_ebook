package com.example.epub.core.epub;
import com.example.epub.core.integration.epub.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EpubEnvelopeTest {
    @Test void nonstandardMimeOrderAndCompressionNormalizeWithoutInflatingOrParsingBookContent() throws Exception {
        var files=EpubExtractorTest.fixture(true);byte[] mime=files.remove("mimetype");files.put("mimetype",mime);
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)) {for(var entry:files.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        byte[] source=output.toByteArray();EpubEnvelope.validate(()->new ByteArrayInputStream(source),source.length);
        var reader=new EpubExtractor().extract(source);
        try(var zip=new ZipInputStream(new ByteArrayInputStream(reader.reader()))) {var first=zip.getNextEntry();assertEquals("mimetype",first.getName());assertEquals(ZipEntry.STORED,first.getMethod());}
        EpubExtractorTest.put(files,"OEBPS/book.opf","<invalid");byte[] invalidXml=EpubExtractorTest.zip(files);
        EpubEnvelope.validate(()->new ByteArrayInputStream(invalidXml),invalidXml.length);
        assertThrows(EpubExtractor.Invalid.class,()->new EpubExtractor().extract(invalidXml));
    }
    @Test void corruptDirectoryMissingMimeAndWrongMimeAreRejectedBeforeQuotaReservation() throws Exception {
        var good=EpubExtractorTest.fixture(false);byte[] source=EpubExtractorTest.zip(good);
        EpubEnvelope.validate(()->new ByteArrayInputStream(source),source.length);
        byte[] count=source.clone();var buffer=ByteBuffer.wrap(count).order(ByteOrder.LITTLE_ENDIAN);buffer.putShort(count.length-22+10,(short)65535);
        assertThrows(IOException.class,()->EpubEnvelope.validate(()->new ByteArrayInputStream(count),count.length));
        byte[] altered=source.clone();altered[38]='x';assertThrows(IOException.class,()->EpubEnvelope.validate(()->new ByteArrayInputStream(altered),altered.length));
        good.remove("mimetype");byte[] missing=EpubExtractorTest.zip(good);assertThrows(IOException.class,()->EpubEnvelope.validate(()->new ByteArrayInputStream(missing),missing.length));
    }
}
