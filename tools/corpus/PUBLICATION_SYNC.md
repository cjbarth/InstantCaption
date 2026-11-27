# Experimental publication preparation

This independent Python 3.10+ utility tests publication-file synchronization before Android integration. It downloads no content unless explicitly asked, uses a cache outside Git checkouts, and never uploads or scrapes HTML. No publications or derived corpora are distributed.

From the repository root:

    python tools/corpus/publication_sync.py --only ijwbq
    python tools/corpus/publication_sync.py --only ijwbq --sync

The first command only prints the plan. The second requests public EPUB metadata and downloads available Indonesian and English pairs. The default cache is outside the repository. Run only one synchronizer process per cache. The previous pair remains active if either new download fails. Unreferenced files may remain. Checksum metadata is not a signature. Actual endpoint compatibility has not been verified. Publication issue dates are candidates, not a verified meeting calendar. Explicit issue identifiers are required for Awake! and the public Watchtower. This tool does not yet align, index, schedule, or activate the downloads in Android.

Reference interface: https://github.com/elimorals/jw-agent-toolkit/blob/main/docs/referencia/jw-core.md
Publisher terms: https://www.jw.org/en/terms-of-use/

Verify the permitted public electronic download mechanisms before distribution. Do not commit downloaded publications, derived text, or indexes. The intended application is free and noncommercial.
