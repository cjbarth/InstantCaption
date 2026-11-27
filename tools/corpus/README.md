# Local translation reference material (experimental)

The HY-MT2 CPU translator can use a local bilingual TSV file as optional translation context. No reference material is bundled. Other translation models and speech recognition do not use this feature.

In Settings, import a UTF-8 TSV file with five fields: type (term or passage), source language, target language, source text, target text. Use lowercase two- or three-letter language codes. Comments start with #. The file must contain at most 5000 distinct entries and 1 MiB. Each text field has a limit of 360 UTF-16 code units. Earlier duplicates take priority. The sample file uses synthetic data only.

Import replaces the prior corpus. Clear reference material removes it. The corpus uses app-private no-backup storage. Retrieval is lexical and bounded; it does not guarantee accurate translation. Compare with the source captions.

Run the standalone checks from the repository root:

    javac -d /tmp/corpus-tests app/src/main/java/com/localcaption/instant/CorpusContext.java tools/corpus/CorpusContextTest.java
    java -cp /tmp/corpus-tests com.localcaption.instant.CorpusContextTest

Android build, live latency and real model inference remain unverified. Automatic preparation, publisher downloads, bilingual alignment, scalable indexing and speech-recognition biasing are separate work. Do not commit downloaded publications or derived corpora.

## Experimental publication synchronization

The desktop Python 3.10+ reference tool prepares EPUB downloads separately from inference. It does not run in Android or automatically align or index material.

From the repository root, preview one publication (no network):

    python tools/corpus/publication_sync.py --only ijwbq

To explicitly allow network access:

    python tools/corpus/publication_sync.py --only ijwbq --sync

Only English and Indonesian publisher codes are configured. The default cache is outside the checkout. Run only one process against a cache at a time. The registry contains publication symbols, not content. Dates are candidate issue IDs, not a verified meeting calendar; use --issue SYMBOL:ISSUE for irregular issues. Updates validate sizes, recognized checksums and basic EPUB structure, and publish both language files together via an atomic manifest. No automatic cache cleanup is implemented. The interface and checksum formats have not been confirmed against live publisher responses.

The endpoint is described in https://github.com/elimorals/jw-agent-toolkit/blob/main/docs/referencia/jw-core.md and https://github.com/MrCyjaneK/jwapi/blob/master/libjw/structs/ApisPubMediaGETPUBMEDIALINKS.go. See https://www.jw.org/en/terms-of-use/ for download and distribution conditions. Do not scrape HTML or commit downloaded files, extracts or derived indexes. Verify permission and actual responses before distributing an integrated downloader.

To propose paragraph matches from personally downloaded EPUB files:

    python tools/corpus/epub_candidates.py source.epub target.epub id en /tmp/candidate.tsv

Shared paragraph IDs are only candidate evidence, not proof of translation equivalence. Review rows before import. Keep output outside the checkout. This helper does not contact the publisher.

Run synthetic downloader tests:

    python -m unittest discover -s tools/corpus -p 'test_*.py' -v

Remaining work: verify actual publication metadata and stable Finder references, discover irregular issues, align bilingual paragraphs reliably, implement an indexed Android corpus and an explicit Update reference library action, test offline operation and latency, and evaluate speech recognition biasing. No publisher content is included.
