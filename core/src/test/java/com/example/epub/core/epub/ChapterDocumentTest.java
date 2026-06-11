package com.example.epub.core.epub;
import com.example.epub.core.integration.epub.ChapterDocument;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChapterDocumentTest {
    @Test void slicesTwoChaptersInOneXhtmlAcrossNestedContainers() throws Exception {
        byte[] xml=("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Book</title></head><body><section><h1 id='mở'>Một</h1><p>First 😀</p><div><h2 id='hai'>Hai</h2><p>Second</p></div></section></body></html>").getBytes(StandardCharsets.UTF_8);
        String first=ChapterDocument.slice(xml,"mở","hai"),second=ChapterDocument.slice(xml,"hai",null);
        assertTrue(first.contains("First"));assertFalse(first.contains("Second"));assertTrue(second.contains("Second"));assertFalse(second.contains("First"));
        assertThrows(java.io.IOException.class,()->ChapterDocument.slice(xml,"missing",null));
    }
    @Test void rejectsDoctypeRatherThanResolvingExternalEntities() {
        assertThrows(java.io.IOException.class,()->ChapterDocument.slice("<!DOCTYPE html SYSTEM 'file:///never-read'><html/>".getBytes(StandardCharsets.UTF_8),null,null));
    }
}
