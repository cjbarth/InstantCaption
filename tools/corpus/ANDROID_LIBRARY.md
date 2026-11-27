# Android publication preparation (experimental)

1. Install the portable debug APK built by GitHub Actions. Its application ID is com.localcaption.instant.corpus, separate from upstream.
2. Select Indonesian speech, English subtitles and HY-MT2. Download the speech and translation models.
3. In Settings choose Update reference library. Confirm the download and use Wi-Fi. The status reports successful reference entries, unavailable publications and retained cached pairs. You can cancel the update.
4. Optional: enable Update weekly (unmetered connection, charging, device idle). Starting captions requests cancellation of any in-progress update; wait until it stops before trying again.
5. Test captions in airplane mode with and without the reference material. Clear reference material deletes manual and downloaded references. Manual TSV import is independent of publisher downloads.

The synchronizer uses public JWPUB and EPUB file metadata, not HTML scraping or JW Library internals. It downloads a pair only if either version changes, validates declared sizes and recognized checksums, and prefers matching JWPUB MEPS document/paragraph IDs and Bible verse IDs, falling back to EPUB chapter basenames and paragraph IDs. A failed pair retains the previous data. There is no publisher content in source control or APK. Matching IDs are candidate alignments, not proof of equivalent meaning. The corpus index is bounded at 8 MiB/30,000 rows per manual or downloaded corpus. It retrieves lexical matches, not semantic embeddings; the model can still mistranslate.

Verified references: https://www.jw.org/en/library/magazines/ and https://www.jw.org/id/perpustakaan/majalah/ expose the GETPUBMEDIALINKS download option; the September 2026 Watchtower metadata is publicly available for both languages. The documented JSON structure matches the parser's file URL, size and checksum fields. The initial irregular issue identifiers g 202611 and wp 202609 come from the publisher's magazine pages. The app checks a small rolling window for newer issue candidates; this is not a complete issue catalog or a verified meeting calendar. Actual app HTTP calls and alignment against real publications remain unverified. JWPUB decoding is implemented experimentally; see JWPUB.md for details and the outstanding permission question. No speech-recognition vocabulary changes were made: the Indonesian portable ASR route uses Whisper ONNX models, not the English/Chinese streaming transducers.

## Verification

The Android build workflow runs the standalone corpus, JWPUB decoder and Python synchronization tests before building and checking a portable APK. It also compiles the instrumentation test. On an Android device with the debug APK and its test APK installed, run the synthetic test:

    adb shell am instrument -w -e mode publication-corpus com.localcaption.instant.corpus.test/com.localcaption.instant.OfflineTest

This test is not a real-model accuracy test. Verify with actual Indonesian speech and personally downloaded publications, then evaluate latency, battery use and faithfulness of translations. The source remains a draft until those checks pass.
