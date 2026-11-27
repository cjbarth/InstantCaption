package com.localcaption.instant;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Read-only local JWPUB parser; joins MEPS document + data-pid and BibleVerseId.
 * No rendering, HTML execution, network access, or modification of publications.
 */
final class JwpubAlignment {
    private static final long MAX_ARCHIVE=256L*1024*1024, MAX_DB=256L*1024*1024, MAX_TEXT=128L*1024*1024;
    private static final int MAX_ROWS=60000;
    private static void check()throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update interrupted");
    }
    private static ZipEntry unique(ZipFile zip,String name)throws IOException{
        ZipEntry found=null;Enumeration<? extends ZipEntry> entries=zip.entries();int count=0;
        while(entries.hasMoreElements()){
            ZipEntry entry=entries.nextElement();
            if(++count>30000)throw new IOException("Too many archive entries");
            if(entry.getName().equals(name)){
                if(found!=null||entry.isDirectory())throw new IOException("Ambiguous archive member");
                found=entry;
            }
        }
        if(found==null)throw new IOException("Missing archive member "+name);
        return found;
    }
    private static void copy(ZipFile zip,ZipEntry e,OutputStream out,long limit,MessageDigest digest)throws IOException{
        if(e.getSize()<0||e.getSize()>limit)throw new IOException("Archive member exceeds limit");
        try(InputStream in=zip.getInputStream(e)){
            byte[] b=new byte[65536];long total=0;int n;
            while((n=in.read(b))!=-1){
                check();if((total+=n)>limit||total>e.getSize())throw new IOException("Expanded archive exceeds limit");
                out.write(b,0,n);if(digest!=null)digest.update(b,0,n);
            }
            if(total!=e.getSize())throw new IOException("Incomplete archive member");
        }
    }
    private static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder();
        for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static final class Rows{
        final Map<String,String> values=new LinkedHashMap<>();
        final Set<String> duplicate=new HashSet<>();
        long text;
        void add(String id,String value)throws IOException{
            value=value.replaceAll("[\\s\\p{Z}\\p{Cc}]+"," ").trim();
            if(value.isEmpty()||value.length()>360||!value.codePoints().anyMatch(Character::isLetterOrDigit))return;
            if(duplicate.contains(id))return;
            if(values.containsKey(id)){values.remove(id);duplicate.add(id);return;}
            if(values.size()+duplicate.size()>=MAX_ROWS)throw new IOException("Publication row limit exceeded");
            values.put(id,value);
        }
        void account(String html)throws IOException{
            text+=html.length()*2L;if(text>MAX_TEXT)throw new IOException("Publication text limit exceeded");
        }
    }
    private static void documents(SQLiteDatabase db,JwpubCipher cipher,Rows rows)throws Exception{
        if(!hasTable(db,"Document"))return;
        // CursorWindow is bounded; exclude unusually large blobs rather than loading them.
        try(Cursor c=db.rawQuery("SELECT MepsDocumentId, Content FROM Document WHERE MepsDocumentId>0 AND length(Content) BETWEEN 16 AND "+JwpubCipher.MAX_BLOB+" ORDER BY MepsDocumentId",null)){
            while(c.moveToNext()){
                check();long doc=c.getLong(0);String html=cipher.decode(c.getBlob(1));rows.account(html);
                Element body=Jsoup.parse(html,"",Parser.htmlParser().setMaxDepth(128)).body();
                body.select("script,style,svg,[data-fnid],.footnote,.fn").remove();
                for(Element p:body.select("p[data-pid],li[data-pid],h1[data-pid],h2[data-pid],h3[data-pid],h4[data-pid]")){
                    String pid=p.attr("data-pid");
                    if(pid.matches("[0-9]{1,10}"))rows.add("d:"+doc+":"+pid,p.text());
                }
            }
        }
    }
    private static boolean hasTable(SQLiteDatabase db,String name){
        try(Cursor c=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",new String[]{name})){
            return c.moveToFirst();
        }
    }
    private static void verses(SQLiteDatabase db,JwpubCipher cipher,Rows rows)throws Exception{
        if(!hasTable(db,"BibleVerse"))return;
        try(Cursor c=db.rawQuery("SELECT BibleVerseId, Content FROM BibleVerse WHERE BibleVerseId>0 AND length(Content) BETWEEN 16 AND "+JwpubCipher.MAX_BLOB+" ORDER BY BibleVerseId",null)){
            while(c.moveToNext()){
                check();String html=cipher.decode(c.getBlob(1));rows.account(html);
                Element body=Jsoup.parse(html,"",Parser.htmlParser().setMaxDepth(128)).body();
                body.select("script,style,svg,[data-fnid],.footnote,.fn,.verseNum,.chapterNum").remove();
                rows.add("v:"+c.getLong(0),body.text());
            }
        }
    }
    private static final class PublicationRows{
        final int language;final String symbol;final long issue;final Rows rows;
        PublicationRows(int language,String symbol,long issue,Rows rows){this.language=language;this.symbol=symbol;this.issue=issue;this.rows=rows;}
    }
    private static PublicationRows read(File file,File scratch,String requested,String issue,String language)throws Exception{
        File contents=File.createTempFile("jwpub-inner-",".zip",scratch),dbFile=null;
        try{
            dbFile=File.createTempFile("jwpub-db-",".db",scratch);
            JSONObject manifest;
            try(ZipFile outer=new ZipFile(file)){
                ByteArrayOutputStream json=new ByteArrayOutputStream();
                copy(outer,unique(outer,"manifest.json"),json,1024*1024,null);
                manifest=new JSONObject(json.toString("UTF-8"));
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(OutputStream out=new FileOutputStream(contents)){
                    copy(outer,unique(outer,"contents"),out,MAX_ARCHIVE,digest);
                }
                String hash=manifest.optString("hash","");
                if(hash.matches("(?i)[0-9a-f]{64}")&&!hex(digest.digest()).equalsIgnoreCase(hash))
                    throw new IOException("Inner archive hash mismatch");
            }
            JSONObject metadata=manifest.getJSONObject("publication");
            String name=metadata.getString("fileName");
            if(!name.matches("[A-Za-z0-9_.-]{1,160}\\.db"))throw new IOException("Unexpected database filename");
            try(ZipFile inner=new ZipFile(contents);OutputStream out=new FileOutputStream(dbFile)){
                copy(inner,unique(inner,name),out,MAX_DB,null);
            }
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)){
                int index,year;long issueId;String symbol;
                try(Cursor c=db.rawQuery("SELECT MepsLanguageIndex, Symbol, Year, IssueTagNumber FROM Publication LIMIT 2",null)){
                    if(!c.moveToFirst())throw new IOException("Missing publication identity");
                    index=c.getInt(0);symbol=c.getString(1);year=c.getInt(2);issueId=c.getLong(3);
                    if(c.moveToNext())throw new IOException("Ambiguous publication identity");
                }
                if(metadata.getInt("language")!=index||!symbol.equals(metadata.getString("symbol"))||metadata.getInt("year")!=year)
                    throw new IOException("Publication identity disagrees with manifest");
                String undated=metadata.optString("undatedSymbol",symbol);
                if(!requested.equals(undated)&&!requested.equals(symbol)&&!symbol.matches(java.util.regex.Pattern.quote(requested)+"[0-9]{2}"))
                    throw new IOException("Unexpected publication symbol");
                if(!issue.isEmpty()&&!(Long.toString(issueId).equals(issue)||Long.toString(issueId).equals(issue+"00")))
                    throw new IOException("Unexpected publication issue");
                // English MEPS index is documented; the other index is obtained from the official language-specific file.
                if(("en".equals(language)&&index!=0)||(!"en".equals(language)&&index==0))
                    throw new IOException("Unexpected publication language");
                Rows rows=new Rows();JwpubCipher cipher=new JwpubCipher(index,symbol,year,issueId);
                // Bible verses take precedence over other documents in a Bible.
                verses(db,cipher,rows);documents(db,cipher,rows);
                return new PublicationRows(index,undated,issueId,rows);
            }
        }finally{
            contents.delete();
            if(dbFile!=null){dbFile.delete();new File(dbFile+"-journal").delete();new File(dbFile+"-wal").delete();new File(dbFile+"-shm").delete();}
        }
    }
    static byte[] align(File source,File target,File scratch,String symbol,String issue,String from,String to)throws Exception{
        PublicationRows a=read(source,scratch,symbol,issue,from),b=read(target,scratch,symbol,issue,to);
        if(a.language==b.language||!a.symbol.equals(b.symbol)||(!issue.isEmpty()&&a.issue!=b.issue))
            throw new IOException("Mismatched publication pair");
        ByteArrayOutputStream out=new ByteArrayOutputStream();int count=0;
        for(Map.Entry<String,String> row:a.rows.values.entrySet()){
            check();String translated=b.rows.values.get(row.getKey());if(translated==null)continue;
            byte[] line=("passage\t"+from+"\t"+to+"\t"+row.getValue()+"\t"+translated+"\n").getBytes(StandardCharsets.UTF_8);
            if(out.size()+line.length>CorpusContext.MAX_BYTES||count>=CorpusContext.MAX_ROWS)break;
            out.write(line);count++;
        }
        if(count==0)throw new IOException("No shared JWPUB identifiers");
        return out.toByteArray();
    }
}
