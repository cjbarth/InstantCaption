package com.localcaption.instant;

import android.app.Instrumentation;
import android.os.Bundle;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import java.security.MessageDigest;
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
    private static byte[] hex(String s){
        byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;
    }
    private static void copy(ZipOutputStream out,String name,File file)throws IOException{
        out.putNextEntry(new ZipEntry(name));
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        out.closeEntry();
    }
    // Synthetic fixtures independently encrypted by Python, with arbitrary non-English test index 25.
    private static File jwpub(File dir,boolean english)throws Exception{
        int lang=english?0:25;
        String doc=english?
            "1050c8c01e137d0b8a4200dbd50b57661e7ea30ac6f9989be8d1b6d166bc160969c24979510de4904d17c893fdbe73da958dc40925a9c31da251478fe0cea4857b2f3ccd82fbe03b15bd7f655a0b644823462d2221427a21d0901e194841009323b47997da9645a59a97ef0d7c9f16cd":
            "d49329490a447282e0ab5ae96757311c8e8dafc76e648852f6523941da06b621ae1a4d9c52d7b1ab714ab1bac99885ac9694808a134b83a023a7d4129e6f5d48cc843ff7b5436854a9b17aaf5cf06fe5b5d5db381e63d4d8dbc99b36693d4d070e6f05def62cb80c4e55d153208359af";
        String verse=english?
            "5445d579d2658c687095416001ebcf71c634b9277d495860facd7ad0ac4bfe9bdd14ba6a9e988dc8011ebb38bfd9505430c77376ed37a9b087905bd99a5adc9a54de84ae51b19d6a31ed754fec51e58d":
            "f0e0c4da854ca470b0a92c70c8a02bcf7bbacc70c84f15755b54b642a990c30f18b62a67e617ce3deedb91be9e05933fc66719058fcbcfaa4fed589eed3ac290";
        File dbFile=File.createTempFile("synthetic-",".db",dir),inner=null,outer=null;
        boolean success=false;
        try{
            try(SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(dbFile,null)){
                db.execSQL("CREATE TABLE Publication (MepsLanguageIndex INTEGER, Symbol TEXT, Year INTEGER, IssueTagNumber INTEGER)");
                db.execSQL("INSERT INTO Publication VALUES (?, 'demo', 2026, 0)",new Object[]{lang});
                db.execSQL("CREATE TABLE Document (DocumentId INTEGER, MepsDocumentId INTEGER, Content BLOB)");
                db.execSQL("INSERT INTO Document VALUES (?, 123456, ?)",new Object[]{english?1:99,hex(doc)});
                db.execSQL("CREATE TABLE BibleVerse (BibleVerseId INTEGER, Content BLOB)");
                db.execSQL("INSERT INTO BibleVerse VALUES (1, ?)",new Object[]{hex(verse)});
            }
            inner=File.createTempFile("synthetic-",".zip",dir);
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(inner))){copy(zip,"demo.db",dbFile);}
            JSONObject manifest=new JSONObject().put("publication",new JSONObject()
                .put("fileName","demo.db").put("symbol","demo").put("year",2026).put("language",lang));
            outer=File.createTempFile("synthetic-",".jwpub",dir);
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(outer))){
                add(zip,"manifest.json",manifest.toString());copy(zip,"contents",inner);
            }
            success=true;return outer;
        }finally{dbFile.delete();if(inner!=null)inner.delete();if(!success&&outer!=null)outer.delete();}
    }
    static void run(Instrumentation test){
        Bundle result=new Bundle();File a=null,b=null,j1=null,j2=null;int status=1;
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
            j1=jwpub(dir,false);j2=jwpub(dir,true);
            byte[] aligned=JwpubAlignment.align(j1,j2,dir,"demo","","id","en");
            String tsv=new String(aligned,StandardCharsets.UTF_8);
            if(CorpusContext.parse(aligned).size()!=2)throw new AssertionError("Expected one document paragraph and one verse");
            if(!tsv.contains("Kami belajar bahasa setiap minggu.\tWe study languages every week.")||
               !tsv.contains("Ini contoh ayat buatan.\tA synthetic example of a verse."))
                throw new AssertionError("JWPUB MEPS/verse alignment failed");
            if(tsv.contains("Duplicate")||tsv.contains("English only"))throw new AssertionError("Ambiguous or unmatched IDs accepted");
            try{JwpubAlignment.align(j1,j2,dir,"other","","id","en");throw new AssertionError("Wrong symbol accepted");}
            catch(IOException expected){}
            result.putString("stream","Synthetic EPUB and JWPUB alignment and indexed retrieval passed");
            status=0;
        }catch(Throwable failure){result.putString("stream",android.util.Log.getStackTraceString(failure));}
        finally{if(a!=null)a.delete();if(b!=null)b.delete();if(j1!=null)j1.delete();if(j2!=null)j2.delete();}
        test.finish(status,result);
    }
}
