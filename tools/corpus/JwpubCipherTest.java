package com.localcaption.instant;
import java.util.Arrays;

public final class JwpubCipherTest {
    private static byte[] hex(String s){
        byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return b;
    }
    public static void main(String[] args)throws Exception{
        // Independent vectors generated with Python hashlib, zlib and cryptography.
        String expected="<p data-pid=\"1\">Synthetic example.</p>";
        byte[] undated=hex("40f940bc564b2a93e0dab15c2d904520c273c66997dcfa7c9d1e7919baad59f701f8434a4d333bc2c6568af410eb4378");
        byte[] dated=hex("8f1368637d012731575f87e6c09e3926481c20b1172b88eed2497853c151ea08c8b7654c319e04c19efd214f7e433cf6");
        if(!expected.equals(new JwpubCipher(0,"demo",2026,0).decode(undated)))throw new AssertionError("Undated vector");
        if(!expected.equals(new JwpubCipher(0,"demo",2026,20261100).decode(dated)))throw new AssertionError("Dated vector");
        try{new JwpubCipher(0,"demo",2026,0).decode(Arrays.copyOf(undated,17));throw new AssertionError("Truncated blob");}catch(java.io.IOException expectedFailure){}
        try{new JwpubCipher(0,"demo",2026,20261100).decode(undated);throw new AssertionError("Wrong card");}catch(javax.crypto.BadPaddingException|java.io.IOException expectedFailure){}
        System.out.println("Four JWPUB decoder checks passed");
    }
}
