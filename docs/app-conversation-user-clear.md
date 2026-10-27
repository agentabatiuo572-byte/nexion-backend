# App conversation USER_VIEW clearing (R20)

## Parent integration status — 2026-10-27

The formal backend implementation and migration are present. Independent backend reviews are
[review A](r20-user-clear-review.md) and [review B](r20-user-clear-review-b.md), with backend items
scored 9.1–9.3. Their separate APP lifecycle findings are not backend persistence failures and
must be closed before signing the complete frontend recovery flow.

- Original final-matrix run: 199 non-DB cases passed; the independent 11-case DB class had
  four fixture failures and one fixture error. That failed 210-case run remains recorded.
- After USER authentication and the project's ObjectMapper were corrected in the independent
  fixture, the actual MySQL rerun passed **11/11, zero failures/errors/skips**. It covers fresh
  service/session graphs, persisted boundaries, original command replay, customer-lock races,
  current staff isolation and staff-record preservation. It is a separate run, not a rewritten
  green 210-case result.
- The later readback-contract correction passed **17/17, zero skips** in
  AppConversationUserClearTest and AppSupportCommandRecoveryTest. Its two new cases distinguish
  persisted terminal clear refusals from nonterminal, wrong-account/scope and unrelated errors.
  It is a service/controller test batch, not another real-MySQL or live-login test.
- Only a retained `SUCCEEDED` command in the current user's exact clear scope, whose stored
  envelope contains one of the two pre-write clear refusals, can return that original refusal
  through command readback. Ordinary conflicts, missing results and UNKNOWN remain uncertain.
- Parent APP interaction evidence uses the real APP build with an isolated loopback fixture:
  rejection preserves history, explicit reconfirmation uses a new key, a lost refusal is read
  back by its original key, and peer changes retain failed text before user-initiated restart.
  This is not formal Java/JWT/WebSocket end-to-end evidence.

The API has **not** been deployed to TEST by this task. Real login/new-device HTTP and WS,
physical devices and production object-storage integration remain unverified. The new migration
must be installed through the canonical installer before API enablement. Local database
credentials, synthetic-state backups and raw logs remain outside the committed source.

The worker notes below retain the implementation sequence and earlier test boundaries;
their earlier "pending matrix" wording is historical and is superseded by the run identities above.

Baseline: `090a7e5032a8f6e781150201ce51005d554890de`; implementation is confined to this backend worktree.
Authority: the APP `conversation-management.ts` contract and
`PRD/UVEL_客服会话连续性与用户侧清空_20261027.md` in the APP worktree.

## Contract and invariants

- GET `/api/app/support/conversation-management`: `clearUserHistory`, `clearedPeers`.
- DELETE `/api/app/support/conversations/{cid}`: authenticated USER, Idempotency-Key,
  `{scope: "USER_VIEW", peerKey, conversationIds, expectedVersions}`.
- Result: `{scope: "USER_VIEW", userScopeCleared: true, peerKey, throughMessageId, conversationNos}`.
- Command readback type: `CONVERSATION_USER_CLEAR`; a retry returns the original boundary,
  never a later one. Message command replay is reprojected from current authorized data:
  clearing through 20 must not hide a later message 30.
- Stable staff IDs identify peers; unassigned conversations are isolated by CID. Never infer identity from names.
- A new reply must match the CID's stored staff identity, including after an account is assigned to another staff member.
  `409 CONVERSATION_PEER_CHANGED` requires refreshing the list/current advisor and entering through create;
  it does not reassign old history. Already committed original-key replies are rehydrated before this new-write gate.
- The server persists both the account/peer boundary and covered CIDs. Reassignment cannot resurrect cleared messages.
- Staff messages, attachments, receipt/audit history and management queries remain intact.
- APP lists/counts/cursors/previews/unread/detail/receipts/replays/media reads enforce visibility;
  websocket commands use those same services, and clearing invalidates the user's other connected views.

## Implementation / validation plan

1. Persist markers and register the replay-safe migration in the startup installer; the deployment broker discovers dated SQL automatically.
2. Implement authorized, versioned, retained-idempotency clear and management readback.
3. Apply visibility before pagination and to every APP message/media/readback path; preserve admin reads.
4. Reuse an existing conversation under the customer mutex only for the matching stable staff ID.
5. Run focused Java service/controller/mapper/replay tests, plus isolated SQL persistence tests if locally available.
   Tests use synthetic accounts only, no application server or existing user database.
6. Record actual results, migration/bootstrap requirements and unverified deployment/device integration below.

No APP edits, real-account clear, deployment, commit, or push are part of this worker scope.
The installed workflow/spec helper is unavailable for `nexion-spec`; the supplied, already-authorized PRD is the specification.
Independent integration review remains the main task's responsibility.

## Validation evidence

- Java 17.0.20.1 / Maven 3.9.9 are installed under `D:/WORKS/PLAN/.local-runtime/phone-calibration-tools`.
  Use explicit `JAVA_HOME` and `apache-maven-3.9.9/bin/mvn.cmd`; quote each `-D...` argument in PowerShell.
- Initial production compilation: green (`r20-compile.log`, ignored local output). The later P2 changes below still require the final matrix.
- `target/r20-targeted.log`: 127 cases, 126 passed; the SQL case initially reused a MyBatis session cache after a raw JDBC simulated external write.
  The test now clears that test session cache after the external write; `target/r20-sql.log` reran the real SQL case successfully (1/1).
  These are distinct scoped results, not a claimed green final integration run.
- The real SQL test used a newly initialized MySQL 8.4.6 data directory in this worktree's `target/`, loopback port 51848.
  It installed the new migration twice, reopened a database session, verified detail/cursor/list/count/unread/media filtering,
  kept message 30 after clear-through-20, retained CID boundaries after staff changes, and compared SHA-256 of original staff messages.
  Its random schema was dropped by the test; only this worker's owned MySQL was shut down afterwards. No shared DB was used.
- Service/HTTP tests cover USER-only access, no-store capability, version/member conflicts, stable identity, canonical request hashing,
  retained clear receipts, current create/reply command replay, and absence of physical-delete fields. These do not claim real JWT/login acceptance.
- Noether owns the independent `R20UserViewClearMySqlReviewTest.java`, including two-connection/latch cases.
  His P2 finding about a snapshot created before waiting on the customer lock is addressed with READ_COMMITTED on
  APP read receipts and attachment upload replay/metadata/content. His changed-staff reply finding is addressed by a current stored-peer check.
  Those incremental fixes and their tests are pending the parent-owned final matrix; no Maven process is left running by this worker.

## Installation and remaining integration

- New forward migration: `scripts/migrations/20261027_app_conversation_user_clear.sql`.
  It creates `nx_app_conversation_user_clear` (account + peer) and `nx_app_conversation_user_clear_member` (covered CIDs).
  It performs no message, attachment, read-receipt, or historical SQL rewrite.
- `scripts/apply_startup_schema_migrations.ps1` explicitly registers it. `scripts/start_ops_console_monolith.ps1` calls that installer.
  Per README, `scripts/schema.sql` and `seed.sql` are only a baseline; use the canonical migration installer for a fresh database too.
  Direct Maven startup does not apply migrations. Install these tables before serving the new API; missing tables fail closed.
- `deploy/public-test/database_migrations.py` automatically inventories `scripts/migrations/YYYYMMDD_*.sql` from the approved artifact.
  No broker code or historical migration hash changed. A test-branch push does not queue deployment;
  Jenkins launch and broker readback remain the main operator's separate responsibility.
- The final parent-run environment is a different isolated MySQL instance on 33327 with its own credentials.
  Credentials remain local and are not part of source/docs. Noether's formal lifecycle test consumes `R20_MYSQL_URL`/user/password;
  this worker's optional mapper test consumes the separate `R20_MYSQL_PORT` and creates/drops a unique `r20_clear_<uuid>` schema.
- APP source, H5, production deployment, real-account clear, and device/browser acceptance were not changed or exercised here.
  Parent integration must verify authenticated HTTP/WS, fresh-session/device behavior and refresh handling for `CONVERSATION_PEER_CHANGED`,
  then record independent review results. The implementation is not represented as already deployed.

## Changed paths

Production contract and visibility:

- `src/main/java/ffdd/opsconsole/content/web/AppConversationManagementController.java`
- `src/main/java/ffdd/opsconsole/content/application/AppConversationManagementService.java`
- `src/main/java/ffdd/opsconsole/content/application/AppConversationVisibilityService.java`
- `src/main/java/ffdd/opsconsole/content/application/AppSupportService.java`
- `src/main/java/ffdd/opsconsole/content/application/OpsConversationService.java`
- `src/main/java/ffdd/opsconsole/content/application/SupportAttachmentService.java`
- `src/main/java/ffdd/opsconsole/content/domain/ConversationUserClear.java`
- `src/main/java/ffdd/opsconsole/content/domain/ContentConversationView.java`
- `src/main/java/ffdd/opsconsole/content/domain/ConversationRepository.java`
- `src/main/java/ffdd/opsconsole/content/infrastructure/MybatisConversationRepository.java`
- `src/main/java/ffdd/opsconsole/content/mapper/AppConversationClearMapper.java`
- `src/main/java/ffdd/opsconsole/content/mapper/AppConversationVisibilitySql.java`
- `src/main/java/ffdd/opsconsole/content/mapper/ConversationMapper.java`
- `src/main/java/ffdd/opsconsole/content/mapper/ConversationMessageMapper.java`
- `src/main/java/ffdd/opsconsole/content/mapper/SupportAttachmentMapper.java`
- `src/main/java/ffdd/opsconsole/content/realtime/ConversationSocketHandler.java`
- `src/main/java/ffdd/opsconsole/shared/security/UserBusinessWriteGateFilter.java`

Installation: the new dated migration and startup registry listed above. Historical migration files and baseline schema are unchanged.

Tests: new `content/application/AppConversationUserClearTest.java` and `content/mapper/AppConversationClearSqlMySqlTest.java`;
updated `AppSupportServiceTest`, `AppSupportCommandRecoveryTest`, `OpsConversationServiceTest`, `SupportAttachmentServiceTest`,
`CustomerConversationReuseTest`, `MybatisConversationRepositoryTest`, `ConversationSocketHandlerTest`,
`UserBusinessWriteGateFilterTest`, and `StartupSchemaMigrationContractTest` under `src/test/java/ffdd/opsconsole/`.
The existing test-only `SupportTestDependencies` isolates the new visibility collaborator for legacy tests;
`SupportTicketCreationMySqlTest` and `ConversationSocketPersistenceMySqlIntegrationTest` constructor wiring was updated accordingly.
The new SQL test exercises actual production mappers rather than that legacy projection substitute.

Parent final matrix should include the above unit classes plus Noether's independent review test after he declares its source ready.
Do not run multiple Maven jobs against this shared worktree's `target/`.
