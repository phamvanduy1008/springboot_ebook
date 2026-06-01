package com.example.epub.worker;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class LocalImportPathsTest {
    @TempDir Path directory;
    @Test void importManifestCannotEscapeExplicitRootOrSelectAnotherFileType() throws Exception {
        Path root=Files.createDirectory(directory.resolve("source")).toRealPath();Files.write(root.resolve("book.epub"),new byte[]{1});Files.write(directory.resolve("outside.epub"),new byte[]{2});Files.write(root.resolve("note.txt"),new byte[]{3});
        assertEquals(root.resolve("book.epub"),LocalImportRunner.confinedSource(root,"book.epub"));
        for(String path:new String[]{"../outside.epub",directory.resolve("outside.epub").toString(),"missing.epub","note.txt",""})assertThrows(IOException.class,()->LocalImportRunner.confinedSource(root,path));
    }
}
