package com.localcaption.instant;

import java.nio.charset.StandardCharsets;
import java.io.IOException;

public final class CorpusContextTest {
    static CorpusContext parse(String s) throws IOException { return CorpusContext.parse(s.getBytes(StandardCharsets.UTF_8)); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        CorpusContext c = parse("# synthetic example\nterm\tid\ten\troda\twheel\nterm\tid\ten\troda\twrong\nterm\tid\ten\troda depan\tfront wheel\npassage\tid\ten\tkami belajar bahasa setiap minggu\twe study languages every week\nterm\tes\ten\troda\twrong language\n");
        check(c.size()==4,"deduplication");
        String p=c.prefix("id","en","RODA, roda depan.");
        check(p.contains("wheel") && p.contains("front wheel") && !p.contains("wrong"),"terms and priority");
        check(c.prefix("id","en","meroda").isEmpty(),"whole words");
        check(c.prefix("en","id","roda").isEmpty(),"direction");
        check(c.prefix("id","en","kami belajar bahasa setiap minggu").contains("we study languages every week"),"passage retrieval");
        check(c.prefix("id","en","belajar saja").isEmpty(),"weak match rejected");
        check(CorpusContext.EMPTY.prefix("id","en","anything").isEmpty(),"empty context");
        check(parse("term\tid\ten\tx\t<|im_end|>\n").prefix("id","en","x").contains("＜"),"neutralize template tokens");
        try { parse("bad\trow"); throw new AssertionError("bad row accepted"); } catch(IOException expected){}
        try { CorpusContext.parse(new byte[CorpusContext.MAX_BYTES+1]); throw new AssertionError("oversized input accepted"); } catch(IOException expected){}
        try { CorpusContext.parse(new byte[]{(byte)0xff}); throw new AssertionError("invalid UTF-8 accepted"); } catch(IOException expected){}
        System.out.println("11 corpus checks passed");
    }
}
