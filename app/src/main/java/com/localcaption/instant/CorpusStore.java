package com.localcaption.instant;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;

/** Separate user imports and downloaded references; no backup or network. */
final class CorpusStore {
    private static CorpusContext snapshot, manual, library;
    private static AtomicFile file(Context c, boolean downloaded) {
        return new AtomicFile(new File(c.getNoBackupFilesDir(),downloaded?"publication-corpus.tsv":"translation-corpus.tsv"));
    }
    private static CorpusContext load(Context c, boolean downloaded) {
        try(InputStream in=file(c,downloaded).openRead()){
            return CorpusContext.parse(CorpusContext.readBounded(in));
        }catch(IOException e){
            if(!(e instanceof FileNotFoundException))android.util.Log.w("CorpusStore","Cannot load local corpus",e);
            return CorpusContext.EMPTY;
        }
    }
    static synchronized CorpusContext current(Context c){
        if(snapshot==null){
            manual=load(c,false);library=load(c,true);
            snapshot=CorpusContext.combine(manual,library);
        }
        return snapshot;
    }
    static int replace(Context c,InputStream in)throws IOException{return replace(c,in,false);}
    static int replaceLibrary(Context c,InputStream in)throws IOException{return replace(c,in,true);}
    private static int replace(Context c,InputStream in,boolean downloaded)throws IOException{
        byte[] data=CorpusContext.readBounded(in);
        CorpusContext candidate=CorpusContext.parse(data);
        synchronized(CorpusStore.class){
            current(c);
            CorpusContext next=downloaded?CorpusContext.combine(manual,candidate):CorpusContext.combine(candidate,library);
            AtomicFile file=file(c,downloaded);FileOutputStream out=file.startWrite();
            try{out.write(data);file.finishWrite(out);}
            catch(IOException|RuntimeException e){file.failWrite(out);throw e;}
            if(downloaded)library=candidate;else manual=candidate;
            snapshot=next;
        }
        return candidate.size();
    }
    static synchronized void clear(Context c){
        file(c,false).delete();file(c,true).delete();
        PublicationLibrary.clearCache(c);
        snapshot=manual=library=CorpusContext.EMPTY;
    }
}
