# Cregis exception ownership and claims

The fixed network is BSC 56 / Cregis 2510, USDT token `0x55d398326f99059ff775485246999027b3197955`.
No request may override that tuple, the configured project, or the HTTPS callback issuer.

## Ownership

The server independently checks provider project membership and its deposit trade, the exact canonical
receipt `Transfer` at `logIndex`, amount, token, recipient, sender, block identity and at least 15 confirmations.
It reads the token sender from `Transfer.topics[1]`; transaction `from` and user supplied sender fields are not evidence.
The sender must have empty contract code at the transfer block and must sign the issued EIP191 message.

The message binds the configured HTTPS callback origin/path and project, action `CLAIM_CREDIT` or
`REFUND_ORIGIN`, network/token, transaction/log index, gross amount, original recipient, sender, target
user, snapshot hash, random nonce and UTC expiry. Challenges expire after ten minutes, and accepted
evidence is usable within five seconds. A persisted nonce is consumed once inside the case transaction.
The signature is not a wallet login, private key request, or general spending authorization.

Exchange and contract senders without an EOA signature remain under investigation. A public transaction,
login, screenshot, arbitrary evidence reference or two administrator approvals cannot establish ownership.

## Claims

Only a still-unattributed observation with no existing deposit event is claimable. A later address
allocation does not establish ownership of an earlier transfer. Existing assigned deposit events cannot
be reassigned through this endpoint. The target must remain an active, non-sandbox user.

| Method | Route | Input |
|---|---|---|
| POST | `/api/admin/finance/cregis/exception-ownership/challenges` | `sourceType`, string `sourceId`, `action`, nullable string `targetUserId` |
| POST | `/api/admin/finance/cregis/exception-claims/preview` | `challengeId`, `signature`, string `targetUserId` |
| GET | `/api/admin/finance/cregis/exception-claims` | `beforeId=0`, `limit=20` |
| POST | `/api/admin/finance/cregis/exception-claims` | preview input plus `evidenceHash`, `reason`; `Idempotency-Key` |
| POST | `/api/admin/finance/cregis/exception-claims/{caseId}/decision` | `expectedVersion`, `APPROVE` or `REJECT`, `reason`; `Idempotency-Key` |

The list returns `{records,hasMore,nextBeforeId}`. IDs/CIDs/customer IDs are strings. Approval requires
another current active administrator with D1 read/channel-management permission and a fresh matching
provider/chain snapshot. Claim fee is the existing one-USDT deposit fee; gross amounts at or below that
fee cannot be credited. The existing wallet, cumulative deposit, ledger, deposit order, reserve and source
attribution are written atomically with required audit and retained command result.

## Source mutex and recovery

The primary key `(project_id,normalized_txid,log_index)` prevents claim, refund, normal automatic credit
and existing hold release from consuming one transfer twice. Reservation ownership includes operation
kind/reference and immutable CID/address/gross snapshot. Refund submission uncertainty keeps ownership
reserved. Completion updates the original observation/event, and resolved unallocated addresses become
`RETIRED`, never `READY` or `UNASSIGNED`. They remain scanned; new deposits create a new hold.

No claim, refund completion or address retirement reopens the global assign/credit/payout switches.
Fresh provider/chain evidence and ordinary switch approval remain separate decisions.

Claim proposal and decision honor A2 locks on `D/deposit/CR-<cid>` and `C/user/<targetUserId>`.
Approval rechecks that its maker remains an active administrator. An enabled environment permits
`IDLE` with the automatic credit bit enabled, or a checked individual repair only while `BLOCKED`
for the exact reason `CREGIS_DEPOSIT_INCIDENT` (this still-unattributed source) or
`CREGIS_UNRESOLVED_EXPOSURE_500`. Individual repair does not enable automatic credit. The exposure
reason remains repairable after its balance falls below 500 so the final source cannot self-lock.
Ordinary `IDLE` credit-off, emergencies, unknown gate reasons, other critical incidents and disputes
remain blocked. Other independent unattributed observations can be resolved one at a time.

A re-mined transaction may have a new block-global log index. Recovery uses the immutable original
`Transfer.from`, recipient and amount to require exactly one fresh canonical match. New credits persist
that original sender; legacy rows without it cannot relocate to another block/hash. The source mutex
keeps both old and new log indices `CREDITED` with a persisted alias, and CID checks block every later
claim/refund nonce for the already credited transfer. Financial deposit orders and positive ledger
history retain their original identity; the recovery operation records the new canonical location.

Crypto implementation uses Bouncy Castle `bcprov-jdk18on` 1.86 and Keccak-256, not SHA3-256.
References: [EIP191](https://eips.ethereum.org/EIPS/eip-191),
[Bouncy Castle Java releases](https://www.bouncycastle.org/download/bouncy-castle-java/).

The isolated source-lock suite is `CregisExceptionSourceMySqlTest`, enabled with
`CREGIS_EXCEPTION_MYSQL_IT=true` and the `CREGIS_EXCEPTION_MYSQL_JDBC`, `_USER`, `_PASSWORD`
environment values. It only accepts a loopback MySQL schema named `cregis_exception_test`.
The suite exercises actual Spring transactions and the production MyBatis source mapper with
two independent connections. It does not contact Cregis or any business wallet/database.
