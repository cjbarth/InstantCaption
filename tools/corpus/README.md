# Local translation reference material (experimental)

The HY-MT2 CPU translator can use a local bilingual TSV file as optional translation context. No reference material is bundled. Other translation models and speech recognition do not use this feature.

In Settings, import a UTF-8 TSV file with five fields: type (term or passage), source language, target language, source text, target text. Use lowercase two- or three-letter language codes. Comments start with #. The file must contain at most 5000 distinct entries and 1 MiB. Each text field has a limit of 360 UTF-16 code units. Earlier duplicates take priority. The sample file uses synthetic data only.

Import replaces the prior corpus. Clear reference material removes it. The corpus uses app-private no-backup storage. Retrieval is lexical and bounded; it does not guarantee accurate translation. Compare with the source captions.

Run the standalone checks from the repository root:

    javac -d /tmp/corpus-tests app/src/main/java/com/localcaption/instant/CorpusContext.java tools/corpus/CorpusContextTest.java
    java -cp /tmp/corpus-tests com.localcaption.instant.CorpusContextTest

Android build, live latency and real model inference remain unverified. Automatic preparation, publisher downloads, bilingual alignment, scalable indexing and speech-recognition biasing are separate work. Do not commit downloaded publications or derived corpora.
