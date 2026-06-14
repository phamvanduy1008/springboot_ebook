package com.example.epub.core.integration.epub;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/** Bounded offline reflowable EPUB2/3 extractor; deliberately conservative CSS. */
public final class EpubExtractor {
    public static final String VERSION="local-epub-3", SANITIZER="allowlist-3";
    public static final int MAX_SOURCE=64*1024*1024, MAX_ENTRY=16*1024*1024, MAX_TOTAL=256*1024*1024, MAX_ENTRIES=10000;
    private static final String XHTML="http://www.w3.org/1999/xhtml";
    public static final class Invalid extends IOException {
        private final String code;
        public Invalid(String code) {super(code);this.code=code;}
        public String code() {return code;}
    }
    public record Spine(String href,String title,boolean linear,String rawText) { }
    public record Toc(int parent,int ordinal,String label,Integer spineIndex,String fragment,boolean chapter) { }
    public record Resource(String href,String mediaType,byte[] bytes) { }
    public record Result(byte[] reader,String title,List<Spine> spine,List<Toc> toc,List<Resource> resources,String coverHref,String tocSource) { }
    private record Item(String id,String path,String output,String media,String properties) { }
    private record Target(String path,String fragment) { }
    private static Invalid invalid() {return new Invalid("EPUB_INVALID");}
    private static byte[] utf(String value) {return value.getBytes(StandardCharsets.UTF_8);}
    private static String local(Node node) {return node.getLocalName()==null?node.getNodeName():node.getLocalName();}
    private static List<Element> children(Node parent) {
        var result=new ArrayList<Element>();for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e)result.add(e);return result;
    }
    private static List<Element> all(Element parent,String name) {
        var result=new ArrayList<Element>();var nodes=parent.getElementsByTagNameNS("*",name);for(int i=0;i<nodes.getLength();i++)result.add((Element)nodes.item(i));return result;
    }
    private static String firstText(Element parent,String name,String fallback) {var found=all(parent,name);return found.isEmpty()?fallback:found.getFirst().getTextContent().strip();}
    private static String cleanLabel(String value) {String label=value.replaceAll("\\s+"," ").strip();return label.isEmpty()?"Untitled":label.substring(0,Math.min(500,label.length()));}
    private static byte[] inertDoctype(byte[] bytes) throws Exception {
        // Recognize only the inert standard XHTML declaration; every other DTD remains forbidden.
        String text=new String(bytes,StandardCharsets.UTF_8);
        String without=text.replaceFirst("<!DOCTYPE\\s+html(?:\\s+PUBLIC\\s+[\"']-//W3C//DTD XHTML 1\\.[01]//EN[\"']\\s+[\"']http://www\\.w3\\.org/TR/xhtml1[01]/DTD/xhtml1[01]\\.dtd[\"'])?\\s*>","")
            .replaceFirst("<!DOCTYPE\\s+ncx\\s+PUBLIC\\s+[\"']-//NISO//DTD ncx 2005-1//EN[\"']\\s+[\"']http://www\\.daisy\\.org/z3986/2005/ncx-2005-1\\.dtd[\"']\\s*>","");
        if(!without.equals(text))bytes=utf(without);
        return bytes;
    }
    private static Document xml(byte[] bytes) throws Exception {
        bytes=inertDoctype(bytes);
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth","64");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/elementAttributeLimit","100");
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxXMLNameLimit","1000");
        var builder=factory.newDocumentBuilder();builder.setEntityResolver((a,b)->{throw invalid();});
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {@Override public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {throw e;}});
        var doc=builder.parse(new ByteArrayInputStream(bytes));if(doc.getElementsByTagName("*").getLength()>100000)throw new Invalid("EPUB_LIMIT_EXCEEDED");return doc;
    }
    private static Document xhtml(byte[] bytes) throws Exception {
        try {return xml(bytes);} catch(org.xml.sax.SAXParseException broken) {
            String text=new String(inertDoctype(bytes),StandardCharsets.UTF_8),upper=text.toUpperCase(Locale.ROOT);
            if(upper.contains("<!DOCTYPE")||upper.contains("<!ENTITY")||broken.getMessage().startsWith("JAXP"))throw invalid();
            if(text.chars().filter(c->c=='<').count()>100000)throw new Invalid("EPUB_LIMIT_EXCEEDED");
            var parser=org.jsoup.parser.Parser.htmlParser().setMaxDepth(64);
            var recovered=org.jsoup.Jsoup.parse(text,"",parser);
            var document=new org.jsoup.helper.W3CDom().fromJsoup(recovered);
            var stack=new ArrayDeque<Map.Entry<Node,Integer>>();stack.push(Map.entry(document.getDocumentElement(),1));int elements=0;
            while(!stack.isEmpty()) {
                var current=stack.pop();if(current.getValue()>64||++elements>100000||current.getKey().getAttributes().getLength()>100)throw new Invalid("EPUB_LIMIT_EXCEEDED");
                for(Node child=current.getKey().getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element)stack.push(Map.entry(child,current.getValue()+1));
            }
            return document;
        }
    }
    private static Set<String> obfuscatedFonts(Map<String,byte[]> files) throws Exception {
        if(!files.containsKey("META-INF/encryption.xml"))return Set.of();
        var entries=all(xml(files.get("META-INF/encryption.xml")).getDocumentElement(),"EncryptedData");
        if(entries.isEmpty())throw new Invalid("EPUB_DRM_UNSUPPORTED");
        var paths=new HashSet<String>();
        for(var entry:entries) {
            var methods=all(entry,"EncryptionMethod");var references=all(entry,"CipherReference");
            if(methods.size()!=1||references.size()!=1||!methods.getFirst().getAttribute("Algorithm").equals("http://www.idpf.org/2008/embedding"))throw new Invalid("EPUB_DRM_UNSUPPORTED");
            var target=resolve("root",references.getFirst().getAttribute("URI"));if(target.fragment()!=null)throw new Invalid("EPUB_DRM_UNSUPPORTED");paths.add(target.path());
        }
        return paths;
    }
    private static byte[] serialize(Document document) throws Exception {
        var factory=TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
        var transformer=factory.newTransformer();transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8");transformer.setOutputProperty(OutputKeys.METHOD,"xml");
        var output=new ByteArrayOutputStream();transformer.transform(new DOMSource(document),new StreamResult(output));return output.toByteArray();
    }
    private static String archivePath(String name) throws Invalid {
        if(name.isEmpty()||name.length()>1000||name.startsWith("/")||name.contains("\\")||name.indexOf(':')>=0||name.indexOf('?')>=0||name.indexOf('#')>=0||name.codePoints().anyMatch(Character::isISOControl)||name.contains("%"))throw invalid();
        String checked=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String segment:checked.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals(".."))throw invalid();
        if(checked.split("/").length>32)throw new Invalid("EPUB_LIMIT_EXCEEDED");return name;
    }
    private static Target resolve(String base,String href) throws Invalid {
        try {
            if(href==null||href.isEmpty()||href.length()>12000||href.contains("\\")||href.codePoints().anyMatch(Character::isISOControl)||href.startsWith("/")||href.matches("(?i)^[a-z][a-z0-9+.-]*:.*"))throw invalid();
            int hash=href.indexOf('#');String path=hash<0?href:href.substring(0,hash),fragment=hash<0?null:URLDecoder.decode(href.substring(hash+1).replace("+","%2B"),StandardCharsets.UTF_8);
            if(path.contains("?")||path.contains("%25")||fragment!=null&&(fragment.length()>1000||fragment.codePoints().anyMatch(Character::isISOControl)))throw invalid();
            path=URLDecoder.decode(path.replace("+","%2B"),StandardCharsets.UTF_8);
            String combined=path.isEmpty()?base:base.substring(0,base.lastIndexOf('/')+1)+path;
            var segments=new ArrayList<String>();
            for(String segment:combined.split("/",-1)) {
                if(segment.equals("."))continue;
                if(segment.equals("..")) {if(segments.isEmpty())throw invalid();segments.removeLast();} else {if(segment.isEmpty())throw invalid();segments.add(segment);}
            }
            String normalized=String.join("/",segments);archivePath(normalized);return new Target(normalized,fragment==null||fragment.isEmpty()?null:fragment);
        } catch(IllegalArgumentException e) {throw invalid();}
    }
    private static Map<String,byte[]> archive(byte[] source) throws Exception {
        if(source.length>MAX_SOURCE)throw new Invalid("EPUB_LIMIT_EXCEEDED");
        preflightDirectory(source);
        var result=new LinkedHashMap<String,byte[]>();var names=new HashSet<String>();long total=0;int count=0;
        try(var zip=ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(source)).get()) {
            var entries=zip.getEntriesInPhysicalOrder();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();String name=archivePath(entry.getName());
                if(++count>MAX_ENTRIES||entry.getSize()>MAX_ENTRY||entry.getSize()<0||entry.getCompressedSize()<0||entry.getSize()>Math.max(1024*1024,entry.getCompressedSize()*200))throw new Invalid("EPUB_LIMIT_EXCEEDED");
                if(!names.add(name)||entry.isUnixSymlink()||!zip.canReadEntryData(entry)||entry.getGeneralPurposeBit().usesEncryption())throw invalid();
                if(entry.isDirectory())continue;
                try(var input=zip.getInputStream(entry)) {
                    byte[] bytes=input.readNBytes(MAX_ENTRY+1);total+=bytes.length;
                    if(bytes.length>MAX_ENTRY||total>MAX_TOTAL)throw new Invalid("EPUB_LIMIT_EXCEEDED");
                    var crc=new CRC32();crc.update(bytes);if(bytes.length!=entry.getSize()||crc.getValue()!=entry.getCrc())throw invalid();result.put(name,bytes);
                }
            }
        }
        if(!Arrays.equals(result.get("mimetype"),utf("application/epub+zip")))throw invalid();
        if(result.containsKey("META-INF/rights.xml"))throw new Invalid("EPUB_DRM_UNSUPPORTED");
        return result;
    }
    private static void preflightDirectory(byte[] source) throws Invalid {
        // Bound the central directory before Commons Compress allocates entry objects.
        if(source.length<22||source[0]!='P'||source[1]!='K'||source[2]!=3||source[3]!=4)throw invalid();
        var buffer=java.nio.ByteBuffer.wrap(source).order(java.nio.ByteOrder.LITTLE_ENDIAN);int end=-1;
        for(int i=source.length-22;i>=Math.max(0,source.length-65557);i--)if(buffer.getInt(i)==0x06054b50&&(buffer.getShort(i+20)&65535)==source.length-i-22) {end=i;break;}
        if(end<0||buffer.getShort(end+4)!=0||buffer.getShort(end+6)!=0)throw invalid();
        int count=buffer.getShort(end+10)&65535;
        if(count>MAX_ENTRIES)throw new Invalid("EPUB_LIMIT_EXCEEDED");
        long size=Integer.toUnsignedLong(buffer.getInt(end+12)),offset=Integer.toUnsignedLong(buffer.getInt(end+16));
        if(count!=(buffer.getShort(end+8)&65535)||offset+size!=end||offset>source.length||count==0)throw invalid();
        int cursor=(int)offset,actual=0;
        while(cursor<end) {
            if(cursor+46>end||buffer.getInt(cursor)!=0x02014b50)throw invalid();
            if(++actual>MAX_ENTRIES)throw new Invalid("EPUB_LIMIT_EXCEEDED");
            cursor+=46+(buffer.getShort(cursor+28)&65535)+(buffer.getShort(cursor+30)&65535)+(buffer.getShort(cursor+32)&65535);
        }
        if(cursor!=end||actual!=count)throw invalid();
    }
    private static byte[] required(Map<String,byte[]> files,String path) throws Invalid {byte[] value=files.get(path);if(value==null)throw invalid();return value;}
    private static final Set<String> TAGS=Set.of("html","head","body","title","meta","link","style","section","article","aside","nav","header","footer","main","div","span","p","br","hr","h1","h2","h3","h4","h5","h6","a","img","figure","figcaption","strong","em","b","i","u","s","small","sup","sub","blockquote","pre","code","ul","ol","li","dl","dt","dd","table","thead","tbody","tfoot","tr","th","td","caption","colgroup","col");
    private static final Set<String> DROP=Set.of("script","iframe","object","embed","form","input","button","textarea","select","audio","video","source","base","math","svg","canvas","template");
    private static final Set<String> ATTRS=Set.of("id","class","title","lang","dir","alt","width","height","colspan","rowspan","start");
    private static final Set<String> CSS=Set.of("color","font-family","font-size","font-weight","font-style","line-height","text-align","text-indent","text-decoration","white-space","margin","margin-left","margin-right","margin-top","margin-bottom","padding","border","border-collapse","display","max-width","width","height","vertical-align","page-break-before","page-break-after","break-before","break-after");
    private static String declarations(String input) {
        if(input.length()>65536||input.contains("\\")||input.contains("/*")||input.contains("@"))return "";
        var output=new StringBuilder();
        for(String pair:input.split(";")) {int colon=pair.indexOf(':');if(colon<1)continue;String property=pair.substring(0,colon).strip().toLowerCase(Locale.ROOT),value=pair.substring(colon+1).strip();
            if(CSS.contains(property)&&value.length()<300&&value.matches("[a-zA-Z0-9#%.,() /+-]+")&&!value.toLowerCase(Locale.ROOT).matches(".*(url|expression|behavior|import|javascript).*"))output.append(property).append(':').append(value).append(';');}
        return output.toString();
    }
    private static String css(String input) {
        if(input.length()>MAX_ENTRY||input.contains("\\")||input.contains("/*")||input.contains("@"))return "";
        var output=new StringBuilder();int cursor=0,count=0;
        while(cursor<input.length()&&++count<2000) {
            int open=input.indexOf('{',cursor);if(open<0)break;int close=input.indexOf('}',open+1);if(close<0)break;int nested=input.indexOf('{',open+1);if(nested>=0&&nested<close)return "";
            String selector=input.substring(cursor,open).strip();if(selector.length()<1024&&selector.matches("[a-zA-Z0-9_ .,#>*:+-]+"))output.append(selector).append('{').append(declarations(input.substring(open+1,close))).append('}');cursor=close+1;
        }
        return output.toString();
    }
    private static String css(String input,String base,Map<String,Item> byPath) {
        if(input.contains("\\")||input.contains("/*"))return "";
        var output=new StringBuilder();var faces=Pattern.compile("(?i)@font-face\\s*\\{([^{}]*)}").matcher(input);
        while(faces.find()) {
            var family=Pattern.compile("(?i)font-family\\s*:\\s*['\"]?([a-zA-Z0-9 _-]{1,80})['\"]?\\s*[;}]?").matcher(faces.group(1));
            var url=Pattern.compile("(?i)url\\(\\s*['\"]?([^'\"()\\s]+)['\"]?\\s*\\)").matcher(faces.group(1));
            if(family.find()&&url.find())try {var item=byPath.get(resolve(base,url.group(1)).path());if(item!=null&&!item.media().startsWith("image/")&&!item.media().equals("text/css")&&!item.media().equals("application/xhtml+xml"))output.append("@font-face{font-family:\"").append(family.group(1).strip()).append("\";src:url(\"").append(item.output().substring(5)).append("\");}");}catch(Invalid ignored) { }
        }
        return output.append(css(faces.replaceAll(""))).toString();
    }
    private static String link(String base,String href,Map<String,Item> byPath,boolean image) {
        try {var target=resolve(base,href);var item=byPath.get(target.path());if(item==null||image&&!item.media().startsWith("image/"))return null;
            return item.output().substring("EPUB/".length())+(target.fragment()==null?"":"#"+target.fragment());
        } catch(Invalid ignored) {return null;}
    }
    private static Node clean(Node source,Document output,String base,Map<String,Item> byPath) {
        if(source.getNodeType()==Node.TEXT_NODE||source.getNodeType()==Node.CDATA_SECTION_NODE)return output.createTextNode(source.getNodeValue());
        if(!(source instanceof Element element))return null;
        String tag=local(source).toLowerCase(Locale.ROOT);if(DROP.contains(tag))return null;
        if(!TAGS.contains(tag)) {var fragment=output.createDocumentFragment();for(Node child=source.getFirstChild();child!=null;child=child.getNextSibling()) {var safe=clean(child,output,base,byPath);if(safe!=null)fragment.appendChild(safe);}return fragment;}
        if(tag.equals("meta"))return null;
        var result=output.createElementNS(XHTML,tag);
        for(String attr:ATTRS)if(element.hasAttribute(attr)&&element.getAttribute(attr).length()<=1000)result.setAttribute(attr,element.getAttribute(attr));
        if(tag.equals("a")&&!result.hasAttribute("id")&&element.hasAttribute("name")&&!element.getAttribute("name").isEmpty()&&element.getAttribute("name").length()<=1000)result.setAttribute("id",element.getAttribute("name"));
        String inline=declarations(element.getAttribute("style"));if(!inline.isEmpty())result.setAttribute("style",inline);
        if(tag.equals("a")&&element.hasAttribute("href")) {String value=link(base,element.getAttribute("href"),byPath,false);if(value!=null)result.setAttribute("href",value);}
        if(tag.equals("img")) {String value=link(base,element.getAttribute("src"),byPath,true);if(value==null)return null;result.setAttribute("src",value);}
        if(tag.equals("link")) {var target=link(base,element.getAttribute("href"),byPath,false);if(!element.getAttribute("rel").equalsIgnoreCase("stylesheet")||target==null||!target.endsWith(".css"))return null;result.setAttribute("rel","stylesheet");result.setAttribute("href",target);}
        if(tag.equals("style")) {result.setTextContent(css(element.getTextContent(),base,byPath));return result;}
        for(Node child=source.getFirstChild();child!=null;child=child.getNextSibling()) {var safe=clean(child,output,base,byPath);if(safe!=null)result.appendChild(safe);}return result;
    }
    private static String raw(Node node) {
        var output=new StringBuilder();raw(node,output);return output.toString().replace("\r\n","\n").replace('\r','\n').replaceAll("[\\t \\x0B\\f]+"," ").replaceAll(" *\n *","\n").replaceAll("\n{3,}","\n\n").strip();
    }
    private static void raw(Node node,StringBuilder output) {
        if(node.getNodeType()==Node.TEXT_NODE) {output.append(node.getNodeValue());return;}
        if(Set.of("head","style").contains(local(node)))return;
        for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())raw(child,output);
        if(Set.of("p","div","section","br","li","h1","h2","h3","h4","h5","h6","tr").contains(local(node)))output.append('\n');
    }
    public Result extract(byte[] source) throws IOException {
        try {return extractChecked(source);} catch(Invalid failure) {throw failure;} catch(Exception failure) {throw invalid();}
    }
    private Result extractChecked(byte[] source) throws Exception {
        var files=archive(source);var container=xml(required(files,"META-INF/container.xml"));var roots=all(container.getDocumentElement(),"rootfile");if(roots.size()!=1)throw invalid();
        String opf=archivePath(roots.getFirst().getAttribute("full-path"));var pkg=xml(required(files,opf)).getDocumentElement();
        if(!Set.of("2.0","3.0").contains(pkg.getAttribute("version")))throw new Invalid("EPUB_UNSUPPORTED");
        for(var meta:all(pkg,"meta"))if((meta.getAttribute("property").equals("rendition:layout")&&meta.getTextContent().strip().equals("pre-paginated"))||meta.getAttribute("name").equals("fixed-layout")&&meta.getAttribute("content").equalsIgnoreCase("true"))throw new Invalid("EPUB_FIXED_LAYOUT_UNSUPPORTED");
        var blockedFonts=obfuscatedFonts(files);var foundFonts=new HashSet<String>();
        var items=new LinkedHashMap<String,Item>();var byPath=new HashMap<String,Item>();int index=0;String cover=null,ncx=null,nav=null;
        for(var element:all(pkg,"item")) {
            String id=element.getAttribute("id"),media=element.getAttribute("media-type"),properties=element.getAttribute("properties");var target=resolve(opf,element.getAttribute("href"));
            if(id.isBlank()||items.containsKey(id)||target.fragment()!=null)throw invalid();
            String extension=switch(media) {case "application/xhtml+xml"->"xhtml";case "text/css"->"css";case "image/jpeg"->"jpg";case "image/png"->"png";case "image/gif"->"gif";case "font/ttf","application/x-font-ttf","application/vnd.ms-opentype"->"ttf";case "font/otf"->"otf";case "font/woff","application/font-woff"->"woff";case "font/woff2"->"woff2";default->null;};
            if(blockedFonts.contains(target.path())) {
                if(extension==null||!Set.of("ttf","otf","woff","woff2").contains(extension))throw new Invalid("EPUB_DRM_UNSUPPORTED");
                foundFonts.add(target.path());extension=null;
            }
            if(media.equals("application/x-dtbncx+xml"))ncx=target.path();
            if(Arrays.asList(properties.split("\\s+")).contains("nav"))nav=target.path();
            Item item=new Item(id,target.path(),extension==null?null:"EPUB/r"+(index++)+"."+extension,media,properties);items.put(id,item);
            if(item.output()!=null) {required(files,item.path());if(byPath.put(item.path(),item)!=null)throw invalid();if(item.media().startsWith("image/")&&Arrays.asList(properties.split("\\s+")).contains("cover-image"))cover=item.output();}
        }
        if(!foundFonts.containsAll(blockedFonts))throw new Invalid("EPUB_DRM_UNSUPPORTED");
        for(var meta:all(pkg,"meta"))if(meta.getAttribute("name").equals("cover")) {var item=items.get(meta.getAttribute("content"));if(item!=null&&item.media().startsWith("image/"))cover=item.output();}
        var texts=new HashMap<String,String>();var titles=new HashMap<String,String>();var resources=new ArrayList<Resource>();var ids=new HashMap<String,Set<String>>();int nodeBudget=500000;long artifactBytes=0;
        for(var item:byPath.values().stream().sorted(Comparator.comparing(Item::output)).toList()) {
            byte[] bytes=files.get(item.path());
            if(item.media().equals("application/xhtml+xml")) {
                var sourceDoc=xhtml(bytes);nodeBudget-=sourceDoc.getElementsByTagName("*").getLength();if(nodeBudget<0)throw new Invalid("EPUB_LIMIT_EXCEEDED");if(!local(sourceDoc.getDocumentElement()).equals("html"))throw invalid();var document=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();document.appendChild(clean(sourceDoc.getDocumentElement(),document,item.path(),byPath));
                var anchors=new HashSet<String>();for(var element:all(document.getDocumentElement(),"*"))if(element.hasAttribute("id")&&!anchors.add(element.getAttribute("id")))throw invalid();ids.put(item.path(),anchors);texts.put(item.path(),raw(document.getDocumentElement()));titles.put(item.path(),cleanLabel(firstText(document.getDocumentElement(),"title","Untitled")));bytes=serialize(document);
            } else if(item.media().equals("text/css"))bytes=utf(css(new String(bytes,StandardCharsets.UTF_8),item.path(),byPath));
            else if(item.media().startsWith("image/")) {
                if(bytes.length<8||!(item.media().equals("image/png")&&bytes[0]==(byte)137&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'||item.media().equals("image/jpeg")&&bytes[0]==(byte)255&&bytes[1]==(byte)216||item.media().equals("image/gif")&&new String(bytes,0,3,StandardCharsets.US_ASCII).equals("GIF")))throw invalid();
            } else if(bytes.length<4||!(Arrays.equals(Arrays.copyOf(bytes,4),new byte[]{0,1,0,0})||Set.of("OTTO","wOFF","wOF2","true").contains(new String(bytes,0,4,StandardCharsets.US_ASCII))))throw invalid();
            artifactBytes+=bytes.length;
            if(texts.containsKey(item.path()))artifactBytes+=utf(texts.get(item.path())).length;
            if(artifactBytes>MAX_TOTAL)throw new Invalid("EPUB_LIMIT_EXCEEDED");
            resources.add(new Resource(item.output(),item.media(),bytes));
        }
        var spine=new ArrayList<Spine>();var sourceIndexes=new HashMap<String,Integer>();
        for(var ref:all(pkg,"itemref")) {
            if(ref.getAttribute("properties").contains("pre-paginated"))throw new Invalid("EPUB_FIXED_LAYOUT_UNSUPPORTED");
            var item=items.get(ref.getAttribute("idref"));if(item==null||!item.media().equals("application/xhtml+xml")||sourceIndexes.put(item.path(),spine.size())!=null)throw invalid();
            spine.add(new Spine(item.output(),titles.get(item.path()),!ref.getAttribute("linear").equals("no"),texts.get(item.path())));
        }
        if(spine.isEmpty())throw invalid();var toc=new ArrayList<Toc>();String tocSource=nav!=null?"NAV":ncx!=null?"NCX":"FALLBACK";
        try {if(nav!=null) {
            var navDoc=xhtml(required(files,nav));Element tocNav=null;
            for(var candidate:all(navDoc.getDocumentElement(),"nav"))if(Arrays.asList(candidate.getAttributeNS("http://www.idpf.org/2007/ops","type").split("\\s+")).contains("toc")) {tocNav=candidate;break;}
            if(tocNav==null)throw invalid();for(var child:children(tocNav))if(local(child).equals("ol"))navigation(child,nav,-1,false,toc,sourceIndexes,ids,0);
        } else if(ncx!=null) {
            var maps=all(xml(required(files,ncx)).getDocumentElement(),"navMap");if(maps.size()!=1)throw invalid();navigation(maps.getFirst(),ncx,-1,true,toc,sourceIndexes,ids,0);
        }} catch(Invalid navigationFailure) {if(!navigationFailure.code().equals("EPUB_TOC_UNAVAILABLE"))throw navigationFailure;toc.clear();}
        if(toc.isEmpty()) {tocSource="FALLBACK";for(int i=0;i<spine.size();i++)toc.add(new Toc(-1,i,spine.get(i).title(),i,null,false));}
        String title=cleanLabel(firstText(pkg,"title","Untitled"));
        var output=new LinkedHashMap<String,byte[]>();output.put("mimetype",utf("application/epub+zip"));output.put("META-INF/container.xml",utf("<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"EPUB/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"));
        var manifest=new StringBuilder("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");var reading=new StringBuilder();
        for(int i=0;i<resources.size();i++) {var resource=resources.get(i);output.put(resource.href(),resource.bytes());manifest.append("<item id=\"r").append(i).append("\" href=\"").append(resource.href().substring(5)).append("\" media-type=\"").append(resource.mediaType()).append("\"").append(resource.href().equals(cover)?" properties=\"cover-image\"":"").append("/>");}
        for(var item:spine) {int i=0;while(!resources.get(i).href().equals(item.href()))i++;reading.append("<itemref idref=\"r").append(i).append("\" linear=\"").append(item.linear()?"yes":"no").append("\"/>");}
        String identity=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        String language=firstText(pkg,"language","und");if(language.length()>35||!language.matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))language="und";
        var metadata=new StringBuilder();for(String field:List.of("creator","rights","publisher")) {int retained=0;for(var value:all(pkg,field)) {if(++retained>8)break;String plain=value.getTextContent().strip();if(!plain.isEmpty())metadata.append("<dc:").append(field).append('>').append(escape(plain.substring(0,Math.min(4000,plain.length())))).append("</dc:").append(field).append('>');}}
        output.put("EPUB/package.opf",utf("<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"uid\">urn:sha256:"+identity+"</dc:identifier><dc:title>"+escape(title)+"</dc:title><dc:language>"+escape(language)+"</dc:language>"+metadata+"<meta property=\"dcterms:modified\">2000-01-01T00:00:00Z</meta></metadata><manifest>"+manifest+"</manifest><spine>"+reading+"</spine></package>"));
        output.put("EPUB/nav.xhtml",utf("<?xml version=\"1.0\"?><html xmlns=\""+XHTML+"\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Contents</title></head><body><nav epub:type=\"toc\">"+navHtml(toc,spine,-1)+"</nav></body></html>"));
        byte[] reader=zip(output);return new Result(reader,title,List.copyOf(spine),List.copyOf(toc),List.copyOf(resources),cover,tocSource);
    }
    private static void navigation(Element list,String base,int parent,boolean ncx,List<Toc> toc,Map<String,Integer> indexes,Map<String,Set<String>> ids,int depth) throws Invalid {
        if(depth>32||toc.size()>10000)throw new Invalid("EPUB_LIMIT_EXCEEDED");
        for(var entry:children(list)) {
            if(!local(entry).equals(ncx?"navPoint":"li"))continue;
            String label="Untitled",href=null;Element nested=null;
            for(var child:children(entry)) {
                if(ncx&&local(child).equals("navLabel"))label=child.getTextContent();
                if(ncx&&local(child).equals("content"))href=child.getAttribute("src");
                if(!ncx&&Set.of("a","span").contains(local(child))) {label=child.getTextContent();if(local(child).equals("a"))href=child.getAttribute("href");}
                if(!ncx&&local(child).equals("ol"))nested=child;
            }
            Integer targetIndex=null;String fragment=null;
            if(href!=null&&!href.isEmpty()) {var target=resolve(base,href);targetIndex=indexes.get(target.path());fragment=target.fragment();if(targetIndex==null)throw new Invalid("EPUB_TOC_UNAVAILABLE");if(fragment!=null&&!ids.getOrDefault(target.path(),Set.of()).contains(fragment))throw invalid();}
            int ordinal=toc.size();toc.add(new Toc(parent,ordinal,cleanLabel(label),targetIndex,fragment,targetIndex!=null));
            if(ncx)navigation(entry,base,ordinal,true,toc,indexes,ids,depth+1);else if(nested!=null)navigation(nested,base,ordinal,false,toc,indexes,ids,depth+1);
        }
    }
    private static String escape(String value) {return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String navHtml(List<Toc> toc,List<Spine> spine,int parent) {
        var result=new StringBuilder("<ol>");for(var entry:toc)if(entry.parent()==parent) {result.append("<li>");if(entry.spineIndex()==null)result.append("<span>").append(escape(entry.label())).append("</span>");else result.append("<a href=\"").append(spine.get(entry.spineIndex()).href().substring(5)).append(entry.fragment()==null?"":"#"+escape(entry.fragment())).append("\">").append(escape(entry.label())).append("</a>");if(toc.stream().anyMatch(child->child.parent()==entry.ordinal()))result.append(navHtml(toc,spine,entry.ordinal()));result.append("</li>");}return result.append("</ol>").toString();
    }
    private static byte[] zip(Map<String,byte[]> files) throws IOException {
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {for(var file:files.entrySet()) {var entry=new ZipEntry(file.getKey());entry.setTime(315532800000L);if(file.getKey().equals("mimetype")) {var crc=new CRC32();crc.update(file.getValue());entry.setMethod(ZipEntry.STORED);entry.setSize(file.getValue().length);entry.setCrc(crc.getValue());}zip.putNextEntry(entry);zip.write(file.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
}
