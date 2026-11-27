package com.localcaption.instant;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Explicit online preparation only. Translation never calls this class for network access. */
final class PublicationLibrary {
    private static final String ENDPOINT="https://b.jw-cdn.org/apis/pub-media/GETPUBMEDIALINKS";
    private static final long MAX_FILE=150L*1024*1024;
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private static final String[] GENERAL={"ijwbq","ijwyp","ijwcl","ijwfq","ijwbv","ijwhf","mrt","lfs","hdu","lff","lffi","lmd","lfb","od","scl","nwt","it"};
    private static final DateTimeFormatter ISSUE=DateTimeFormatter.ofPattern("yyyyMM",Locale.ROOT);
    private static final Map<String,String> LANG=new HashMap<>();
    static{LANG.put("id","IN");LANG.put("en","E");}
    static boolean supports(String from,String to){return !from.equals(to)&&LANG.containsKey(from)&&LANG.containsKey(to);}
    static boolean running(){return RUNNING.get();}
    private static volatile Thread worker;
    private static volatile boolean cancelled;
    private static volatile HttpURLConnection connection;
    static synchronized boolean start(){if(!RUNNING.compareAndSet(false,true))return false;cancelled=false;return true;}
    static synchronized void finish(){worker=null;connection=null;RUNNING.set(false);}
    static void cancel(){cancelled=true;Thread t=worker;if(t!=null)t.interrupt();HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    private static void checkCancelled()throws InterruptedIOException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update cancelled");}
    private static File dir(Context c){return new File(c.getNoBackupFilesDir(),"publication-library");}
    static boolean hasCache(Context c){String[] files=dir(c).list();return files!=null&&files.length>0;}
    static void clearCache(Context c){if(running())return;File[] files=dir(c).listFiles();if(files!=null)for(File f:files)if(f.isFile())f.delete();}
    private static final class Pub{
        final String symbol,issue;
        Pub(String symbol,String issue){this.symbol=symbol;this.issue=issue;}
        String key(){return symbol+(issue.isEmpty()?"":"-"+issue);}
    }
    private static List<Pub> plan(File dir,String from,String to){
        YearMonth now=YearMonth.now();List<Pub> p=new ArrayList<>();
        // Study issues precede the assigned meeting; prioritize roughly two months back.
        for(int i:new int[]{-2,-1,-3,0,-4,1,2})p.add(new Pub("w",now.plusMonths(i).format(ISSUE)));
        YearMonth workbook=now.minusMonths((now.getMonthValue()-1)%2);
        p.add(new Pub("mwb",workbook.format(ISSUE)));p.add(new Pub("mwb",workbook.plusMonths(2).format(ISSUE)));
        // Verified publication identifiers from the publisher's magazine download page.
        // Keep a known issue until a more recent issue has been cached. Probe a small
        // rolling window of publication-file metadata, not the website itself.
        for(String symbol:new String[]{"g","wp"}){
            String latest=symbol.equals("g")?"202611":"202609";
            String prefix=from+"-"+to+"-"+symbol+"-";
            String[] cached=dir.list();
            if(cached!=null)for(String name:cached)if(name.startsWith(prefix)&&name.endsWith(".bin")){
                String issue=name.substring(prefix.length(),name.length()-4);
                if(issue.matches("[0-9]{6}")&&issue.compareTo(latest)>0)latest=issue;
            }
            p.add(new Pub(symbol,latest));
            for(int i=-1;i<=2;i++){
                String candidate=now.plusMonths(i).format(ISSUE);
                if(candidate.compareTo(latest)>0)p.add(new Pub(symbol,candidate));
            }
        }
        for(String symbol:GENERAL)p.add(new Pub(symbol,""));
        return p;
    }
    private static URL allowed(String value)throws Exception{
        URL url=new URL(value);String host=url.getHost().toLowerCase(Locale.ROOT);
        if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null|| (url.getPort()!=-1&&url.getPort()!=443) ||
            !(host.equals("jw.org")||host.endsWith(".jw.org")||host.equals("jw-cdn.org")||host.endsWith(".jw-cdn.org")))
            throw new IOException("Unexpected publisher download host");
        return url;
    }
    private static HttpURLConnection open(String value)throws Exception{
        URL url=allowed(value);
        for(int redirects=0;redirects<=5;redirects++){
            checkCancelled();
            HttpURLConnection c=(HttpURLConnection)url.openConnection();connection=c;
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent","PersonalCorpusPrototype/0.1");
            c.setRequestProperty("Accept-Encoding","identity");
            try{
                int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){
                    String next=c.getHeaderField("Location");
                    if(next==null)throw new IOException("Missing redirect");
                    url=allowed(new URL(url,next).toString());c.disconnect();continue;
                }
                if(status!=200)throw new IOException("HTTP "+status);
                return c;
            }catch(Exception e){c.disconnect();throw e;}
        }
        throw new IOException("Too many redirects");
    }
    private static final class Remote{
        String url,checksum,modified,format;long size;
        String identity(){return format+":"+(checksum.isEmpty()?modified:checksum)+":"+size;}
        boolean comparable(){return !checksum.isEmpty()||!modified.isEmpty();}
    }
    private static Remote metadata(Pub pub,String lang,String format)throws Exception{
        String url=ENDPOINT+"?booknum=0&output=json&pub="+pub.symbol+"&fileformat="+format+"&alllangs=0&langwritten="+lang+"&txtCMSLang="+lang+
            (pub.issue.isEmpty()?"":"&issue="+pub.issue);
        HttpURLConnection c=open(url);
        JSONObject payload;
        try(InputStream in=c.getInputStream()){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Metadata too large");out.write(buffer,0,n);}
            payload=new JSONObject(out.toString("UTF-8"));
        }finally{c.disconnect();}
        if(!pub.symbol.equals(payload.optString("pub",pub.symbol)))throw new IOException("Wrong publication");
        String issue=payload.optString("issue","");
        if(!pub.issue.isEmpty()&&!issue.isEmpty()&&!issue.equals(pub.issue)&&!issue.equals(pub.issue+"00"))throw new IOException("Wrong issue");
        JSONArray files=payload.getJSONObject("files").getJSONObject(lang).optJSONArray(format);
        if(files==null||files.length()==0)throw new IOException("No "+format+" file");
        JSONObject row=null;
        for(int i=0;i<files.length();i++){
            JSONObject option=files.getJSONObject(i);
            if(files.length()>1&&(!option.optString("edition","").isEmpty()||!option.optString("format","").isEmpty()))continue;
            if(row!=null)throw new IOException("Ambiguous publication edition");
            row=option;
        }
        if(row==null)throw new IOException("No regular publication edition");
        if(!pub.symbol.equals(row.optString("pub",pub.symbol)))throw new IOException("Wrong publication file");
        if("EPUB".equals(format)&&!"application/epub+zip".equals(row.optString("mimetype","application/epub+zip")))throw new IOException("Not an EPUB file");
        JSONObject file=row.getJSONObject("file");
        Remote r=new Remote();r.format=format;r.url=allowed(file.getString("url")).toString();
        r.checksum=file.optString("checksum","");r.modified=file.optString("modifiedDatetime",file.optString("modifiedDateTime",""));
        r.size=row.optLong("filesize",row.optLong("fileSize",-1));
        if(r.size<=0||r.size>MAX_FILE)throw new IOException("Invalid publication size");
        return r;
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte x:bytes)out.append(String.format(Locale.ROOT,"%02x",x&255));return out.toString();}
    private static File fetch(File dir,Remote r)throws Exception{
        File temp=File.createTempFile("download-","."+r.format.toLowerCase(Locale.ROOT),dir);
        String checksum=r.checksum.toLowerCase(Locale.ROOT);
        String algorithm=checksum.matches("[0-9a-f]{32}")?"MD5":checksum.matches("[0-9a-f]{40}")?"SHA-1":checksum.matches("[0-9a-f]{64}")?"SHA-256":null;
        MessageDigest digest=algorithm==null?null:MessageDigest.getInstance(algorithm);
        try{
            HttpURLConnection c=open(r.url);
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[65536];long total=0;int n;
                while((n=in.read(buffer))!=-1){
                    checkCancelled();
                    if((total+=n)>r.size)throw new IOException("Publication exceeds stated size");
                    out.write(buffer,0,n);if(digest!=null)digest.update(buffer,0,n);
                }
                if(total!=r.size)throw new IOException("Incomplete publication");
                if(digest!=null&&!hex(digest.digest()).equals(checksum))throw new IOException("Publication checksum mismatch");
            }finally{c.disconnect();}
            return temp;
        }catch(Exception e){temp.delete();throw e;}
    }
    private static final class Pair{
        final String left,right;final byte[] rows;
        Pair(String left,String right,byte[] rows){this.left=left;this.right=right;this.rows=rows;}
    }
    private static Pair read(AtomicFile file){
        try(DataInputStream in=new DataInputStream(file.openRead())){
            if(in.readInt()!=1)return null;
            String left=in.readUTF(),right=in.readUTF();int size=in.readInt();
            if(size<=0||size>CorpusContext.MAX_BYTES)return null;
            byte[] rows=new byte[size];in.readFully(rows);if(in.read()!=-1)return null;
            return new Pair(left,right,rows);
        }catch(IOException e){return null;}
    }
    private static void save(AtomicFile file,Pair pair)throws IOException{
        FileOutputStream stream=file.startWrite();
        try{
            DataOutputStream out=new DataOutputStream(stream);out.writeInt(1);out.writeUTF(pair.left);out.writeUTF(pair.right);
            out.writeInt(pair.rows.length);out.write(pair.rows);out.flush();file.finishWrite(stream);
        }catch(IOException|RuntimeException e){file.failWrite(stream);throw e;}
    }
    private static Pair update(File dir,AtomicFile file,Pair old,Pub pub,String from,String to)throws Exception{
        Exception first=null;
        for(String format:new String[]{"JWPUB","EPUB"}){
            try{return updateFormat(dir,file,old,pub,from,to,format);}
            catch(InterruptedException e){throw e;}
            catch(Exception e){checkCancelled();if(first==null)first=e;else e.addSuppressed(first);if("EPUB".equals(format))throw e;}
        }
        throw new IOException("No compatible publication pair",first);
    }
    private static Pair updateFormat(File dir,AtomicFile file,Pair old,Pub pub,String from,String to,String format)throws Exception{
        Remote a=metadata(pub,LANG.get(from),format);Thread.sleep(500);
        Remote b=metadata(pub,LANG.get(to),format);Thread.sleep(500);
        if(old!=null&&a.comparable()&&b.comparable()&&old.left.equals(a.identity())&&old.right.equals(b.identity()))return old;
        File x=null,y=null;
        try{
            x=fetch(dir,a);Thread.sleep(500);y=fetch(dir,b);
            byte[] rows="JWPUB".equals(format)?JwpubAlignment.align(x,y,dir,pub.symbol,pub.issue,from,to):EpubAlignment.align(x,y,from,to);
            Pair result=new Pair(a.identity(),b.identity(),rows);
            // Validate before changing the pair cache.
            if(CorpusContext.parse(result.rows).size()==0)throw new IOException("Empty reference material");
            save(file,result);return result;
        }finally{if(x!=null)x.delete();if(y!=null)y.delete();}
    }
    static String synchronize(Context c,String from,String to,Consumer<String> progress)throws Exception{
        worker=Thread.currentThread();checkCancelled();
        if(!supports(from,to))throw new IOException("Publication downloads currently support Indonesian and English");
        File dir=dir(c);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create reference directory");
        ByteArrayOutputStream all=new ByteArrayOutputStream();
        int done=0,failed=0,cached=0,rows=0;List<Pub> publications=plan(dir,from,to);String firstFailure="";
        for(Pub pub:publications){
            checkCancelled();
            progress.accept("Updating "+pub.key()+" ("+(++done)+"/"+publications.size()+")");
            AtomicFile file=new AtomicFile(new File(dir,from+"-"+to+"-"+pub.key()+".bin"));
            Pair pair=read(file);
            try{pair=update(dir,file,pair,pub,from,to);}
            catch(InterruptedException e){throw e;}
            catch(Exception e){checkCancelled();failed++;if(firstFailure.isEmpty())firstFailure=pub.key()+": "+e.getMessage();if(pair!=null)cached++;}
            if(pair!=null){
                // Keep complete UTF-8 lines and bound the active corpus; weekly material is first.
                int start=0;
                for(int i=0;i<pair.rows.length;i++)if(pair.rows[i]=='\n'){
                    int len=i-start+1;
                    if(all.size()+len>CorpusContext.MAX_BYTES||rows>=CorpusContext.MAX_ROWS)break;
                    all.write(pair.rows,start,len);start=i+1;rows++;
                }
            }
        }
        if(rows==0)throw new IOException("No aligned references; previous library retained. "+firstFailure);
        checkCancelled();
        int count=CorpusStore.replaceLibrary(c,new ByteArrayInputStream(all.toByteArray()));
        String status=count+" reference entries; "+failed+" unavailable or failed, "+cached+" retained from cache"+
            (firstFailure.isEmpty()?"":". First failure: "+firstFailure);
        c.getSharedPreferences("publication-library",0).edit().putString("lastStatus",status).apply();return status;
    }
}
