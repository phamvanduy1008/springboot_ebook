package com.example.epub.core.integration.epub;

import java.io.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.w3c.dom.ranges.DocumentRange;

/** Slice already sanitized XHTML between verified TOC anchors, never reopen the source archive. */
public final class ChapterDocument {
    private ChapterDocument() { }
    public static String slice(byte[] xhtml,String start,String end) throws IOException {
        try {
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xhtml));
            Element body=(Element)document.getElementsByTagNameNS("http://www.w3.org/1999/xhtml","body").item(0);
            if(body==null)throw new IOException("Missing reader body");
            Element first=anchor(body,start),last=end==null?null:anchor(body,end);
            var range=((DocumentRange)document).createRange();range.selectNodeContents(body);
            if(first!=null)range.setStartBefore(first);
            if(last!=null&&(first==null||(first.compareDocumentPosition(last)&Node.DOCUMENT_POSITION_FOLLOWING)!=0))range.setEndBefore(last);
            var contents=range.cloneContents();range.detach();
            while(body.hasChildNodes())body.removeChild(body.getFirstChild());body.appendChild(contents);
            var transformers=TransformerFactory.newInstance();transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
            var transformer=transformers.newTransformer();transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,"yes");
            var output=new StringWriter();transformer.transform(new DOMSource(document),new StreamResult(output));return output.toString();
        } catch(IOException failure) {throw failure;} catch(Exception failure) {throw new IOException("Invalid immutable reader document",failure);}
    }
    private static Element anchor(Element body,String id) throws IOException {
        if(id==null||id.isEmpty())return null;
        if(id.equals(body.getAttribute("id")))return null;
        var elements=body.getElementsByTagName("*");
        for(int i=0;i<elements.getLength();i++) {var element=(Element)elements.item(i);if(id.equals(element.getAttribute("id")))return element;}
        throw new IOException("Missing TOC anchor");
    }
}
