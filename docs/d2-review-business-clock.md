# D2 review business clock and known rejection results

## Problem

The public TEST host uses UTC, while risk timestamps and withdrawal lifecycle dates use the existing Asia/Shanghai business clock. D2 review used the host clock, so a valid K4 score could appear to be in the future and return `K4_RISK_SCORE_UNAVAILABLE`. That rejection was HTTP 503, which the admin command client correctly treats as an uncertain outcome.

An approved bank withdrawal is dispatched asynchronously by `HdPayPayoutScheduler` through `POST /api/order/publicWithdrawal`. A rejection before approval leaves the withdrawal pending and cannot reach the provider create call.

## Change

- Use the injected business `Clock` for D2 risk freshness, review dates, lifecycle release, dispatch prechecks and confirmation timestamps.
- Return HTTP 409 for the exact K3/K4 pre-mutation rejection results. Project matching legacy 503 idempotency replies to 409 without deleting or rewriting receipts.
- Leave other 5xx/unknown outcomes, permissions, request fingerprint validation, coverage requirements and lifecycle rules intact.

Legacy receipt projection only applies when the existing idempotency service returns a retained record. Ordinary review records expire after 24 hours; an expired key must not be treated as a permanently read-only replay. Do not replay a real approval command as a deployment smoke test.

The observed 30-day H1 withdrawal cooldown is a separate business rule. This fix neither removes it nor changes existing withdrawal deadlines.

## Verification (2026-09-16)

Run backend tests with a UTC JVM:

```text
mvn -Duser.timezone=UTC -Dtest=OpsFinanceServiceTest,D2WithdrawalClosureContractTest,OpsFinanceControllerTest,OpsFinanceControllerD2SecurityTest,HdPayPayoutSchedulerTest,HdPayPayoutTransactionsTest,AdminIdempotencyServiceTest,OpsConsoleArchitectureTest,ApiResultHttpStatusAdviceTest test
```

174 tests passed. Regression coverage includes valid business-time risk on a UTC host, future/stale risk rejection, an unexpired cooldown, retained legacy rejection replay without financial mutation, unrelated 5xx preservation, and scheduler business time.

The unchanged admin client outcome and backend-unavailable message contract suites passed all 23 tests. Independent adversarial review found no blocking issue. No real approval or provider payout was executed for validation.

Rollback: revert this code change and redeploy the previous backend artifact. No schema migration or business-data rewrite is involved.
