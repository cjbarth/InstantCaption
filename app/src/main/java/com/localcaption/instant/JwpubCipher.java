package com.localcaption.instant;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.InflaterInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Read-only decoder for the publicly documented JWPUB z-a container convention.
 * Format reference: https://github.com/thenights20/jwpub-toolkit/blob/main/JWPUB_FORMAT.md
 * No publisher files, keys unique to a user, or app internals are included.
 */
final class JwpubCipher {
    private static final byte[] MASK=hex("11cbb5587e32846d4c26790c633da289f66fe5842a3a585ce1bc3a294af5ada7");
    static final int MAX_BLOB=1024*1024, MAX_HTML=4*1024*1024;
    private final byte[] key,iv;
    JwpubCipher(int language,String symbol,int year,long issue)throws Exception{
        if(language<0||symbol==null||!symbol.matches("[A-Za-z0-9_-]{1,80}")||year<0||issue<0)
            throw new IOException("Invalid publication identity");
        String card=language+"_"+symbol+"_"+year+(issue==0?"":"_"+issue);
        byte[] material=MessageDigest.getInstance("SHA-256").digest(card.getBytes(StandardCharsets.UTF_8));
        for(int i=0;i<material.length;i++)material[i]^=MASK[i];
        key=Arrays.copyOfRange(material,0,16);iv=Arrays.copyOfRange(material,16,32);
    }
    String decode(byte[] blob)throws Exception{
        if(blob==null||blob.length==0||blob.length>MAX_BLOB||blob.length%16!=0)
            throw new IOException("Invalid encrypted content length");
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
        byte[] compressed=cipher.doFinal(blob);
        try(InputStream in=new InflaterInputStream(new ByteArrayInputStream(compressed))){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                if(out.size()+n>MAX_HTML)throw new IOException("Decompressed content exceeds limit");
                out.write(buffer,0,n);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(out.toByteArray())).toString();
        }
    }
    private static byte[] hex(String s){
        byte[] b=new byte[s.length()/2];
        for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);
        return b;
    }
}
