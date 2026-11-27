package com.localcaption.instant;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Conservative EPUB alignment: unique matching chapter basenames and paragraph IDs.
 * Matching identifiers are candidates, not evidence of semantic equivalence. */
final class EpubAlignment {
    private static final long MAX_XML=4L*1024*1024, MAX_TOTAL=64L*1024*1024;
    private static final int MAX_CHAPTERS=5000, MAX_PARAGRAPHS=60000;
    static final class Limited extends FilterInputStream {
        long remaining;
        Limited(InputStream in,long limit){super(in);remaining=limit;}
        @Override public int read()throws IOException{
            if(remaining==0){if(super.read()==-1)return -1;throw new IOException("XML exceeds limit");}
            int x=super.read();if(x!=-1)remaining--;return x;
        }
        @Override public int read(byte[] b,int off,int len)throws IOException{
            if(len==0)return 0;
            if(remaining==0)return read();
            int n=super.read(b,off,(int)Math.min(len,remaining));if(n>0)remaining-=n;return n;
        }
    }
    private static Map<String,String> paragraphs(File file)throws Exception{
        Map<String,String> rows=new LinkedHashMap<>();Set<String> ambiguous=new HashSet<>();
        long total=0;int chapters=0;
        try(ZipFile z=new ZipFile(file)){
            ZipEntry mime=z.getEntry("mimetype");
            if(mime==null||mime.getSize()!=20)throw new IOException("Not an EPUB");
            try(InputStream in=z.getInputStream(mime)){
                byte[] bytes=new byte[20];new DataInputStream(in).readFully(bytes);
                if(!"application/epub+zip".equals(new String(bytes,StandardCharsets.US_ASCII))||in.read()!=-1)
                    throw new IOException("Not an EPUB");
            }
            if(z.getEntry("META-INF/container.xml")==null)throw new IOException("Incomplete EPUB");
            Enumeration<? extends ZipEntry> entries=z.entries();
            int archiveEntries=0;
            while(entries.hasMoreElements()){
                if(++archiveEntries>30000)throw new IOException("Too many ZIP entries");
                ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(entry.isDirectory()||!name.toLowerCase(Locale.ROOT).endsWith(".xhtml"))continue;
                if(++chapters>MAX_CHAPTERS||entry.getSize()<0||entry.getSize()>MAX_XML|| (total+=entry.getSize())>MAX_TOTAL)
                    throw new IOException("EPUB XML exceeds limits");
                String chapter=name.substring(name.lastIndexOf('/')+1);
                try(InputStream in=new Limited(z.getInputStream(entry),entry.getSize())){
                    XmlPullParser parser=Xml.newPullParser();
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL,false);
                    parser.setInput(in,null);
                    String id=null;StringBuilder text=null;int depth=-1, paragraphs=0;
                    for(int event=parser.nextToken();event!=XmlPullParser.END_DOCUMENT;event=parser.nextToken()){
                        if(parser.getDepth()>128)throw new IOException("XML nesting exceeds limit");
                        if(event==XmlPullParser.DOCDECL && !"html".equalsIgnoreCase(parser.getText().trim()))
                            throw new IOException("Unsupported XML declaration");
                        if(event==XmlPullParser.START_TAG&&text==null&&"p".equals(parser.getName())){
                            id=parser.getAttributeValue(null,"id");
                            if(id!=null&&!id.isEmpty()&&id.length()<=128){depth=parser.getDepth();text=new StringBuilder();}
                        }else if(text!=null&&(event==XmlPullParser.TEXT||event==XmlPullParser.CDSECT||event==XmlPullParser.ENTITY_REF)){
                            String value=parser.getText();
                            if(value!=null&&text.length()<=360)text.append(value,0,Math.min(value.length(),361-text.length()));
                        }else if(text!=null&&event==XmlPullParser.END_TAG&&depth==parser.getDepth()){
                            if(++paragraphs>MAX_PARAGRAPHS||rows.size()+ambiguous.size()>MAX_PARAGRAPHS)
                                throw new IOException("Too many paragraphs");
                            String value=text.toString().replaceAll("[\\s\\p{Z}\\p{Cc}]+"," ").trim();
                            String key=chapter+"\t"+id;
                            if(!value.isEmpty()&&value.length()<=360&&value.codePoints().anyMatch(Character::isLetterOrDigit)){
                                if(!ambiguous.contains(key)&&rows.putIfAbsent(key,value)!=null){rows.remove(key);ambiguous.add(key);}
                            }
                            text=null;id=null;depth=-1;
                        }
                    }
                }
            }
        }
        return rows;
    }
    static byte[] align(File source,File target,String from,String to)throws Exception{
        Map<String,String> a=paragraphs(source),b=paragraphs(target);
        ByteArrayOutputStream out=new ByteArrayOutputStream();int count=0;
        for(Map.Entry<String,String> row:a.entrySet()){
            String translated=b.get(row.getKey());if(translated==null)continue;
            byte[] line=("passage\t"+from+"\t"+to+"\t"+row.getValue()+"\t"+translated+"\n").getBytes(StandardCharsets.UTF_8);
            if(out.size()+line.length>CorpusContext.MAX_BYTES||count>=CorpusContext.MAX_ROWS)break;
            out.write(line);count++;
        }
        if(count==0)throw new IOException("No shared paragraph identifiers");
        return out.toByteArray();
    }
}
