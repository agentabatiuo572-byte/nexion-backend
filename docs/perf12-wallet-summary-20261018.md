# Wallet summary: canonical compute reward classification

The wallet summary classifies each ledger row before calculating eight aggregates. Normal compute-task and paid Cloud Share NEX settlement both issue `COMPUTE_TASK_REWARD`; a real read-only sample contained 19,310 such rows for one account. Sending this canonical type through the full regular-expression chain adds avoidable work.

The summary adds one branch after the existing trial/check-in branches and before the first regular expression. Both sides of the normalized type comparison are cast to binary, so only the exact bytes of `COMPUTE_TASK_REWARD` return `reward` early. The original classification already returns `reward` for that string. Accented lookalikes, spaces, prefixes and mixed types continue through the unchanged original branches.

`NO_MERGE`, all eight aggregate formulas, decimal and NULL handling, ledger scope, business date bounds, permissions and pagination remain unchanged. This changes no compute receipt, payout, treasury classification, reward amount or earnings-release rule. No index, schema or cache changes are included.

The separate read-only SQL experiment met its predeclared component threshold: the large account's p50/p95 changed from 225/229 ms to 120.5/124 ms with 20/20 paired wins, beyond the measured 21 ms noise; the small account stayed within its noise. These are two-account component measurements, not public-page latency or cold-cache acceptance. The original run failed while decoding its Unicode fixture output; its completed measurements are retained separately. A subsequent HEX-only literal-fixture read supplies the missing classification check and does not replace that failure or rerun the measurements.

Repository verification is recorded with the candidate handoff. Deployment and affected public-page verification remain separate from this source change.
