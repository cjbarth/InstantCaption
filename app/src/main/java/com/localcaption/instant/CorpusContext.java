package com.localcaption.instant;

import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable, bounded local bilingual reference index. No network or Android dependency. */
final class CorpusContext {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_ROWS = 30000;
    private static final int MAX_FIELD = 360;
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");
    static final CorpusContext EMPTY = new CorpusContext(Collections.emptyList());
    private final List<Entry> entries;
    private final Map<String,List<Entry>> passageIndex = new HashMap<>();
    private final List<Entry> terms = new ArrayList<>();
    private final Map<Entry,Integer> order = new IdentityHashMap<>();

    private static final class Entry {
        final boolean term;
        final String sourceLang, targetLang, source, target, normalized;
        final Set<String> words;
        Entry(String[] fields) {
            term = fields[0].equals("term");
            sourceLang = fields[1]; targetLang = fields[2];
            source = fields[3]; target = fields[4];
            normalized = normalize(source);
            words = words(normalized);
        }
    }
    private CorpusContext(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        for (Entry e : entries) {
            order.put(e,order.size());
            if (e.term) terms.add(e);
            else for (String word : e.words)
                passageIndex.computeIfAbsent(e.sourceLang+" "+e.targetLang+" "+word,k->new ArrayList<>()).add(e);
        }
    }
    int size() { return entries.size(); }
    static CorpusContext combine(CorpusContext manual, CorpusContext library) {
        if (manual.size()==0) return library;
        if (library.size()==0) return manual;
        List<Entry> all=new ArrayList<>(manual.entries);
        all.addAll(library.entries);
        return new CorpusContext(all);
    }
    static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = input.read(buffer)) != -1;) {
            if (output.size() + n > MAX_BYTES) throw new IOException("Corpus exceeds 8 MiB");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }
    static CorpusContext parse(byte[] input) throws IOException {
        if (input.length > MAX_BYTES) throw new IOException("Corpus exceeds 8 MiB");
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(input)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { throw new IOException("Corpus must be UTF-8", e); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int lineNo = 0;
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            for (String line; (line = reader.readLine()) != null;) {
                lineNo++;
                if (line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length != 5 || !(f[0].equals("term") || f[0].equals("passage")) ||
                    !f[1].matches("[a-z]{2,3}") || !f[2].matches("[a-z]{2,3}") || f[1].equals(f[2]))
                    throw new IOException("Invalid corpus row " + lineNo);
                for (int i = 3; i < 5; i++) {
                    f[i] = f[i].trim();
                    if (f[i].isEmpty() || f[i].length() > MAX_FIELD || f[i].codePoints().anyMatch(Character::isISOControl))
                        throw new IOException("Invalid text at corpus row " + lineNo);
                }
                Entry e = new Entry(f);
                if (e.normalized.isEmpty()) throw new IOException("No source words at row " + lineNo);
                String key = f[0] + "\t" + f[1] + "\t" + f[2] + "\t" + e.normalized;
                if (seen.add(key)) entries.add(e);
                if (entries.size() > MAX_ROWS) throw new IOException("Corpus exceeds 30000 unique rows");
            }
        }
        return new CorpusContext(entries);
    }
    private static String normalize(String s) {
        return NON_WORD.matcher(Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }
    private static Set<String> words(String normalized) {
        Set<String> out = new HashSet<>();
        for (String word : normalized.split(" ")) if (word.codePointCount(0,word.length()) >= 3) out.add(word);
        return out;
    }
    private static boolean containsPhrase(String text, String phrase) {
        return (" " + text + " ").contains(" " + phrase + " ");
    }
    private static String safe(String s) {
        return s.replace("<|", "＜|").replace("|>", "|＞").replace("<", "＜").replace(">", "＞").replace("[", "［").replace("]", "］");
    }
    /** Select only matching language pairs; bound added prompt text and use lexical overlap. */
    String prefix(String sourceLang, String targetLang, String speech) {
        String normalized = normalize(speech);
        Set<String> query = words(normalized);
        if (normalized.isEmpty()) return "";
        List<Entry> terms = new ArrayList<>();
        Entry passage = null;
        double best = 0;
        Set<Entry> candidates=new LinkedHashSet<>(this.terms);
        if (query.size()>=3) for (String word:query)
            candidates.addAll(passageIndex.getOrDefault(sourceLang+" "+targetLang+" "+word,Collections.emptyList()));
        for (Entry e : candidates) {
            if (!e.sourceLang.equals(sourceLang) || !e.targetLang.equals(targetLang)) continue;
            if (e.term) {
                if (containsPhrase(normalized, e.normalized)) terms.add(e);
            } else if (query.size() >= 3 && e.words.size() >= 3) {
                int overlap = 0;
                for (String w : query) if (e.words.contains(w)) overlap++;
                double coverage = (double) overlap / Math.min(query.size(), e.words.size());
                double score = (double) overlap / (query.size() + e.words.size() - overlap);
                if (overlap >= 3 && coverage >= .70 && score >= .35 && (score > best || (score == best && passage!=null && order.get(e)<order.get(passage)))) {
                    passage = e; best = score;
                }
            }
        }
        terms.sort((a,b) -> {int length=Integer.compare(b.normalized.length(),a.normalized.length());return length!=0?length:Integer.compare(order.get(a),order.get(b));});
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Entry e : terms) {
            String line = safe(e.source) + " translates to " + safe(e.target) + "\n";
            if (line.length() > 220 || out.length() + line.length() > 450) continue;
            if (count == 0) out.append("Reference the following translations:\n");
            out.append(line);
            if (++count == 4) break;
        }
        if (passage != null) {
            out.append("[Background Information]\nReference example (not speech to translate):\n")
                .append(safe(passage.source)).append("\nEquivalent translation: ").append(safe(passage.target))
                .append("\nUse this only as vocabulary context. Do not add content from this example.\n");
        }
        return out.toString();
    }
}
