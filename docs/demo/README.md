# The recorded demo

`splitpix-demo.mp4` is a scripted browser walkthrough of the seeded demo
group, captured by Playwright. Nothing in it is staged: every number comes
from the running application, and the strategy comparison shows real solver
output. Names, keys and amounts are synthetic.

## Regenerate it

```bash
./scripts/record-demo.sh
```

Requirements: Java 21, Docker (the app boots against a throwaway PostgreSQL
via Testcontainers), and Node 18+. The first run downloads Playwright's
Chromium build (~95 MB, cached under `~/Library/Caches/ms-playwright` or the
platform equivalent).

The script, in order:

1. starts the app with `./mvnw spring-boot:test-run`, unless `SPLITPIX_URL`
   points at an instance you already have running;
2. seeds the demo group with `scripts/seed-demo.sh` and asserts, via the
   compare endpoint, that the strategies genuinely diverge on it — the
   recording refuses to proceed on data where every strategy agrees;
3. runs the walkthrough in `e2e/demo.spec.ts` with context-level video
   recording (1440×900);
4. synthesizes the background pad with `scripts/demo-music.py` (standard
   library sine synthesis, four chords, sized to the video's exact length —
   nothing licensed, nothing downloaded) and muxes it in with `ffmpeg-static`
   while converting to H.264, the format GitHub's file viewer plays inline;
5. stops whatever it started and leaves `docs/demo/splitpix-demo.mp4`.

## Why the seed makes the strategies differ

The group's balances are Ana −600, Bruno −400, Clara +600, Diego +400, and
the only payment relationships among people with open balances are Ana–Diego
and Bruno–Clara. Settling in the minimum two transfers therefore forces two
brand-new payment pairs, while the relationship-aware strategy spends a third
transfer to get away with one. Elisa exists to make that state reachable from
real expenses: her boat trip and farewell dinner net to zero but shift credit
between Diego and Clara without relating either of them to the debtors.

## Adjusting the walkthrough

Pacing lives in `e2e/demo.spec.ts` as explicit `beat(...)` pauses on top of
real UI readiness waits; scene order and dwell times are plain code. The
music's chords, level and envelope are constants at the top of
`scripts/demo-music.py`. To swap in a licensed track instead, replace the
generated WAV in `scripts/record-demo.sh` with your file.

None of this runs in CI; it is a portfolio artifact regenerated on demand.
