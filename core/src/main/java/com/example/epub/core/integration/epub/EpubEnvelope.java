package com.example.epub.core.integration.epub;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Check ZIP32 directory and the tiny mimetype entry without inflating book resources or parsing XML. */
public final class EpubEnvelope {
    @FunctionalInterface public interface Opener {InputStream open() throws IOException;}
    private EpubEnvelope() { }
    private static IOException invalid() {return new IOException("Unsupported EPUB envelope");}
    private static ByteBuffer little(byte[] bytes) {return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static byte[] bytes(InputStream input,int length) throws IOException {byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw invalid();return bytes;}
    public static void validate(Opener opener,long size) throws IOException {
        if(size<22||size>EpubExtractor.MAX_SOURCE)throw invalid();
        try(var input=opener.open()) {if(!Arrays.equals(bytes(input,4),new byte[]{'P','K',3,4}))throw invalid();}
        int tailSize=(int)Math.min(size,65557);byte[] tail;
        try(var input=opener.open()) {input.skipNBytes(size-tailSize);tail=bytes(input,tailSize);}
        var buffer=little(tail);int end=-1;
        for(int i=tailSize-22;i>=0;i--)if(buffer.getInt(i)==0x06054b50&&(buffer.getShort(i+20)&65535)==tailSize-i-22) {end=i;break;}
        if(end<0||buffer.getShort(end+4)!=0||buffer.getShort(end+6)!=0)throw invalid();
        int count=buffer.getShort(end+10)&65535;long directorySize=Integer.toUnsignedLong(buffer.getInt(end+12)),offset=Integer.toUnsignedLong(buffer.getInt(end+16)),absoluteEnd=size-tailSize+end;
        if(count<1||count>EpubExtractor.MAX_ENTRIES||count!=(buffer.getShort(end+8)&65535)||offset+directorySize!=absoluteEnd)throw invalid();
        long mimeOffset=-1,consumed=0;
        try(var input=opener.open()) {
            input.skipNBytes(offset);
            for(int i=0;i<count;i++) {
                var entry=little(bytes(input,46));if(entry.getInt(0)!=0x02014b50)throw invalid();
                int nameSize=entry.getShort(28)&65535,extra=entry.getShort(30)&65535,comment=entry.getShort(32)&65535;
                if(nameSize<1||nameSize>1000)throw invalid();
                byte[] name=bytes(input,nameSize);input.skipNBytes((long)extra+comment);consumed+=46L+nameSize+extra+comment;if(consumed>directorySize)throw invalid();
                if(Arrays.equals(name,"mimetype".getBytes(StandardCharsets.US_ASCII))) {
                    if(mimeOffset>=0||entry.getInt(24)!=20||(entry.getShort(8)&1)!=0)throw invalid();
                    mimeOffset=Integer.toUnsignedLong(entry.getInt(42));
                }
            }
        }
        if(consumed!=directorySize||mimeOffset<0||mimeOffset>=offset)throw invalid();
        try(var input=opener.open()) {
            input.skipNBytes(mimeOffset);try(var zip=new ZipInputStream(input,StandardCharsets.UTF_8)) {
                var entry=zip.getNextEntry();if(entry==null||!entry.getName().equals("mimetype")||!Arrays.equals(zip.readNBytes(21),"application/epub+zip".getBytes(StandardCharsets.US_ASCII)))throw invalid();
            }
        }
    }
}
