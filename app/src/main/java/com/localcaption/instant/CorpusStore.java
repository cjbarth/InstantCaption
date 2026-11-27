package com.localcaption.instant;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;

/** Private app-local corpus excluded from Android automatic backup. */
final class CorpusStore {
    private static CorpusContext snapshot;
    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), "translation-corpus.tsv"));
    }
    static synchronized CorpusContext current(Context context) {
        if (snapshot == null) {
            try (InputStream in = file(context).openRead()) {
                snapshot = CorpusContext.parse(CorpusContext.readBounded(in));
            } catch (IOException e) {
                snapshot = CorpusContext.EMPTY;
                if (!(e instanceof FileNotFoundException)) android.util.Log.w("CorpusStore", "Cannot load local corpus", e);
            }
        }
        return snapshot;
    }
    static int replace(Context context, InputStream in) throws IOException {
        byte[] data = CorpusContext.readBounded(in);
        CorpusContext candidate = CorpusContext.parse(data);
        synchronized (CorpusStore.class) {
            AtomicFile file = file(context);
            FileOutputStream out = file.startWrite();
            try {
                out.write(data);
                file.finishWrite(out);
            } catch (IOException | RuntimeException e) {
                file.failWrite(out);
                throw e;
            }
            snapshot = candidate;
        }
        return candidate.size();
    }
    static synchronized void clear(Context context) {
        file(context).delete();
        snapshot = CorpusContext.EMPTY;
    }
}
