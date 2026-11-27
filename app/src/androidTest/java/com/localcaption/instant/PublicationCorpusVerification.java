package com.localcaption.instant;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

/** Device-side synthetic EPUB test, with no publisher content or network. */
final class PublicationCorpusVerification {
    private static void add(ZipOutputStream out,String name,String value)throws IOException{
        out.putNextEntry(new ZipEntry(name));out.write(value.getBytes(StandardCharsets.UTF_8));out.closeEntry();
    }
    private static File epub(File dir,String lang,String paragraph)throws IOException{
        File f=File.createTempFile("synthetic-"+lang,".epub",dir);
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(f))){
            add(out,"mimetype","application/epub+zip");
            add(out,"META-INF/container.xml","<container/>");
            add(out,"OEBPS/chapter.xhtml","<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p id=\"p1\">"+paragraph+
                "</p><p id=\"duplicate\">first</p><p id=\"duplicate\">second</p><p>no identifier</p></body></html>");
        }
        return f;
    }
    static void run(Instrumentation test){
        Bundle result=new Bundle();File a=null,b=null;int status=1;
        try{
            File dir=test.getTargetContext().getCacheDir();
            a=epub(dir,"id","kami belajar bahasa setiap minggu");
            b=epub(dir,"en","we study languages every week");
            byte[] bytes=EpubAlignment.align(a,b,"id","en");
            CorpusContext corpus=CorpusContext.parse(bytes);
            if(corpus.size()!=1)throw new AssertionError("Expected one unique shared paragraph");
            if(!corpus.prefix("id","en","kami belajar bahasa setiap minggu").contains("we study languages every week"))
                throw new AssertionError("Aligned reference not retrieved");
            if(!corpus.prefix("en","id","kami belajar bahasa setiap minggu").isEmpty())
                throw new AssertionError("Wrong language route");
            result.putString("stream","Synthetic EPUB alignment and indexed retrieval passed");
            status=0;
        }catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));}
        finally{if(a!=null)a.delete();if(b!=null)b.delete();}
        test.finish(status,result);
    }
}
