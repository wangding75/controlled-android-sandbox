# C1-GATE provider attempt 03 classification

- **Result:** the current-head provider pressure run failed after
  `elapsed_seconds=720.328`, before the requested 1800 seconds.
- **Observed error:** after repeated successful Provider generations, the Host command returned
  `GUEST_NOT_PREPARED` on the next launch following Guest process reclamation. The raw log had
  repeated `C1_T04_PROVIDER_PASS` markers and no Provider fail, FATAL, ANR, or background-start
  rejection.
- **Classification:** `TEST_EVIDENCE_GAP` in the campaign generation boundary: the synchronized
  loop fenced the prior generation but assumed the broker session remained prepared after an
  externally reclaimed Guest process.
- **Repair:** normal generation launch remains unchanged; if and only if launch returns
  `GUEST_NOT_PREPARED`, the Host now validates `RuntimeClient.prepare()` (`PREPARED` or
  `ALREADY_PREPARED`) and retries that generation once. This keeps process-death/restart recovery
  inside the long-pressure path without adding a blocking prepare call to every healthy cycle.
