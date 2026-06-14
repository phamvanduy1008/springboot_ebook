package com.example.epub.core.epub;
import com.example.epub.core.integration.epub.EpubExtractor;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.epub.core.epub.EpubExtractorTest.*;
class LegacyEpubTest {
 @Test void repairsLegacyHtmlEntitiesParagraphsAndNamedAnchorsBeforeSanitizing() throws Exception {
  var files=fixture(true);
  put(files,"OEBPS/chương.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>Legacy</title></head><body><a name='mở-đầu'></a><p>Legacy&nbsp;😀<p>Second<h2 id='phần-hai'>Two</h2><p>Body<script>EPUB_ATTACK</script></body></html>");
  var result=new EpubExtractor().extract(zip(files));assertEquals(3,result.toc().size());assertTrue(result.spine().getFirst().rawText().contains("Legacy"));assertFalse(result.spine().getFirst().rawText().contains("EPUB_ATTACK"));
  var reopened=new EpubExtractor().extract(result.reader());assertEquals(result.spine().getFirst().rawText(),reopened.spine().getFirst().rawText());assertEquals("mở-đầu",result.toc().getFirst().fragment());
 }
 @Test void knownFontObfuscationUsesFallbackFontButNeverAllowsEncryptedContent() throws Exception {
  var files=fixture(true);String font=files.keySet().stream().filter(p->p.endsWith(".ttf")).findFirst().orElseThrow();
  files.put(font,new byte[]{1,2,3,4});
  String encryption="<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><EncryptedData xmlns='http://www.w3.org/2001/04/xmlenc#'><EncryptionMethod Algorithm='http://www.idpf.org/2008/embedding'/><CipherData><CipherReference URI='"+font+"'/></CipherData></EncryptedData></encryption>";
  put(files,"META-INF/encryption.xml",encryption);var result=new EpubExtractor().extract(zip(files));assertFalse(result.resources().stream().anyMatch(r->r.mediaType().contains("font")));assertEquals(2,result.spine().size());
  put(files,"META-INF/encryption.xml",encryption.replace(font,"OEBPS/chương.xhtml"));assertEquals("EPUB_DRM_UNSUPPORTED",assertThrows(EpubExtractor.Invalid.class,()->new EpubExtractor().extract(zip(files))).code());
  put(files,"META-INF/encryption.xml",encryption.replace("http://www.idpf.org/2008/embedding","http://www.w3.org/2001/04/xmlenc#aes256-cbc"));assertEquals("EPUB_DRM_UNSUPPORTED",assertThrows(EpubExtractor.Invalid.class,()->new EpubExtractor().extract(zip(files))).code());
 }
 @Test void staleLocalTocFileTargetsFallBackToActualSpineWithoutInventingChapters() throws Exception {
  var files=fixture(false);put(files,"OEBPS/toc.ncx",new String(files.get("OEBPS/toc.ncx"),StandardCharsets.UTF_8).replace("chương.xhtml#mở-đầu","missing.xhtml"));
  var result=new EpubExtractor().extract(zip(files));assertEquals("FALLBACK",result.tocSource());assertEquals(2,result.toc().size());assertTrue(result.toc().stream().noneMatch(EpubExtractor.Toc::chapter));
 }
 @Test void malformedHtmlNeverEnablesDtdEntityOrXmlLimitBypasses() throws Exception {
  var files=fixture(true);put(files,"OEBPS/chương.xhtml","<!DOCTYPE html [<!ENTITY secret SYSTEM 'file:///never-read'>]><html><body><p>&secret;<p>bad</body></html>");
  assertThrows(EpubExtractor.Invalid.class,()->new EpubExtractor().extract(zip(files)));
  put(files,"OEBPS/chương.xhtml","<html><body>"+"<div>".repeat(70)+"<p>&nbsp;bad"+"</div>".repeat(70)+"</body></html>");assertThrows(EpubExtractor.Invalid.class,()->new EpubExtractor().extract(zip(files)));
 }
}
