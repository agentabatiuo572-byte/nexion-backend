package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.auth.application.AdminMfaCipher;
import ffdd.opsconsole.auth.application.AdminTotpService;
import ffdd.opsconsole.platform.domain.AuditLockTarget;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.dto.AuditOperationProposalRequest;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in HTTP/MySQL/private-object acceptance. Both avatar-A2 and inherited BULK gates are required.
 * Enable only under a newly authorized resource lease; compile-only and a disabled suite prove no runtime behavior.
 * JWTs use the existing isolated session fixture and real DB grants; this suite does not claim MFA-login coverage.
 */
@EnabledIfEnvironmentVariable(named = "CS_ENHANCE_AVATAR_A2_ENABLED", matches = "true")
@EnabledIfEnvironmentVariable(named = "CS_ENHANCE_BULK_ENABLED", matches = "true")
@SpringBootTest(classes = NexionOpsConsoleApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportEnhancementPreparationTest.IsolatedConfiguration.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SupportAdminAvatarA2RuntimeTest extends SupportBulkRuntimeFixture {
    private static final String ACCOUNTS = "/api/admin/platform/accounts";
    private static final String OPERATIONS = "/api/admin/platform/audit/operations";
    private static final String METHOD = "makerAvatarUsesRealTwoPersonApprovalAndRejectedAttemptsRemainAtomic";
    private final String avatarRun = "avatar_a2_" + UUID.randomUUID().toString().substring(0, 8);
    private final Set<String> accountNames = new LinkedHashSet<>();
    private final Map<Long, String> actorTokens = new LinkedHashMap<>();
    private final Set<String> assetIds = new LinkedHashSet<>();
    @Autowired AdminMfaCipher mfaCipher;
    @Autowired AdminTotpService totp;
    @Autowired AdminAccountStateMapper accountStates;
    private Map<String, Object> originalRules;
    private List<Long> originalEnabled;
    private long maker, checker, third;
    private boolean boundaryReady, validated;

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) {
        SupportEnhancementPreparationTest.isolatedBoundary(registry);
    }

    @BeforeEach
    void prepareOwnFixture() {
        boundary();
        assertThat(System.getenv("CS_ENHANCE_AVATAR_A2_ENABLED")).isEqualTo("true");
        assertThat(System.getenv("WORKFLOW_RUN_ID")).isNotBlank();
        assertThat(System.getenv("WORKFLOW_SNAPSHOT_HASH")).isNotBlank();
        assertThat(System.getenv("CS_ENHANCE_EVIDENCE_DIR")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin WHERE LEFT(username,?)=?", Long.class,
                run.length() + 1, run + "_")).as("New fixture namespace must not include an existing account").isZero();
        boundaryReady = true;
        originalRules = jdbc.queryForMap("SELECT * FROM nx_support_rules WHERE id=1");
        originalEnabled = jdbc.queryForList(
                "SELECT admin_id FROM nx_support_agent_profile WHERE enabled=1 AND is_deleted=0 ORDER BY admin_id", Long.class);
        maker = admin("a2maker", "SUPER_ADMIN", "MANAGER");
        checker = admin("a2checker", "SUPER_ADMIN", "MANAGER");
        third = admin("a2third", "SUPER_ADMIN", "MANAGER");
        for (long actor : List.of(maker, checker, third)) {
            jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?", actor);
            permissions.evict(actor);
            assertThat(permissions.getPermissionCodes(actor)).contains("platform_a1_read", "platform_a1_write",
                    "platform_a2_read", "platform_a2_proposal_create", "platform_a2_operation_approve");
            assertThat(jdbc.queryForObject("SELECT super_admin FROM nx_admin WHERE id=?", Integer.class, actor)).isEqualTo(1);
        }
        for (long actor : List.of(maker, checker)) {
            accountStates.upsertMfaBinding(actor, mfaCipher.encrypt(totp.generateSecret()), LocalDateTime.now());
        }
        actorTokens.put(maker, token(maker));
        actorTokens.put(checker, token(checker));
        actorTokens.put(third, token(third));
    }

    @Test
    void makerAvatarUsesRealTwoPersonApprovalAndRejectedAttemptsRemainAtomic() throws Exception {
        assertThat(maker).isNotEqualTo(checker);
        int originalColor = 0x3355CC, replacementColor = 0xCC3366;
        String staged = uploadAvatar(maker, originalColor);
        String username = ownAccountName("created");
        Map<String, Object> create = createParams(username, staged);
        create.put("_avatarMakerAdminId", third);
        create.put("avatarMakerAdminId", checker);
        AuditOperationProposalRequest request = proposal("a1_account_create", create);
        String proposalKey = key();
        String createdTicket = propose(request, proposalKey);
        JsonNode repeatedProposal = http("POST", OPERATIONS, actorTokens.get(maker), request, proposalKey);
        success(repeatedProposal);
        assertThat(repeatedProposal.path("data").path("id").asText()).isEqualTo(createdTicket);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_operation_ticket WHERE operation_id=?",
                Long.class, createdTicket)).isEqualTo(1L);

        String selfKey = key();
        JsonNode self = fixtureActors().createApproval(username, createdTicket, maker, selfKey, () -> approve(createdTicket, maker, selfKey));
        assertCode(self, 403);
        assertPending(createdTicket);
        assertThat(accountCount(username)).isZero();
        assertAsset(staged, maker, "READY", null);
        passed("self-approval", createdTicket);

        String approveKey = key();
        JsonNode created = fixtureActors().createApproval(username, createdTicket, checker, approveKey, () -> approve(createdTicket, checker, approveKey));
        success(created);
        long target = fixtureActors().actorForCommand(approveKey);
        admins.add(target);
        JsonNode account = account(target);
        assertThat(account.path("avatarAssetId").asText()).isEqualTo(staged);
        assertThat(account.path("avatarVersion").asLong()).isEqualTo(1L);
        assertThat(account.path("credentialDeliveryStatus").asText()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
        assertAsset(staged, maker, "ATTACHED", target);
        assertApprovalActors(createdTicket, target, "A1_OPERATOR_CREATED");
        readImage(ACCOUNTS + "/" + target + "/avatar", actorTokens.get(checker), originalColor);
        JsonNode creationRetry = approve(createdTicket, checker, approveKey);
        success(creationRetry);
        assertThat(creationRetry.path("data")).isEqualTo(created.path("data"));
        assertThat(accountCount(username)).isEqualTo(1L);
        assertThat(account(target).path("avatarVersion").asLong()).isEqualTo(1L);
        passed("create-and-retry", createdTicket);

        String replacement = uploadAvatar(maker, replacementColor);
        Map<String, Object> before = snapshot(target);
        String updatedTicket = propose(proposal("a1_account_update_profile", profileParams(target, replacement)), key());
        String updateKey = key();
        JsonNode updated = approve(updatedTicket, checker, updateKey);
        success(updated);
        Map<String, Object> after = snapshot(target);
        for (String field : List.of("username", "nickname", "email", "status", "super_admin")) {
            assertThat(after.get(field)).as("Unchanged original account field %s", field).isEqualTo(before.get(field));
        }
        assertThat(((Number) after.get("version")).longValue()).isEqualTo(((Number) before.get("version")).longValue() + 1);
        assertThat(((Number) after.get("avatar_version")).longValue()).isEqualTo(2L);
        assertThat(after.get("avatar_asset_id")).isEqualTo(replacement);
        assertAsset(replacement, maker, "ATTACHED", target);
        assertApprovalActors(updatedTicket, target, "A1_OPERATOR_PROFILE_UPDATED");
        readImage(ACCOUNTS + "/" + target + "/avatar", actorTokens.get(checker), replacementColor);
        JsonNode updateRetry = approve(updatedTicket, checker, updateKey);
        success(updateRetry);
        assertThat(updateRetry.path("data")).isEqualTo(updated.path("data"));
        assertThat(snapshot(target)).isEqualTo(after);
        assertThat(account(target).path("avatarAssetId").asText()).isEqualTo(replacement);
        assertThat(account(target).path("avatarVersion").asLong()).isEqualTo(2L);
        passed("update-by-distinct-checker-and-retry", updatedTicket);

        for (long uploader : List.of(checker, third)) {
            String foreign = uploadAvatar(uploader, originalColor);
            assertRejectedUpdate(target, foreign, 404, "READY", null);
            String rejectedUsername = ownAccountName(uploader == checker ? "checker" : "third");
            assertRejectedCreate(rejectedUsername, foreign, 404, "READY");
        }
        passed("checker-and-third-party-ownership", target);

        String stale = uploadAvatar(maker, originalColor);
        String staleVersion = String.valueOf(((Number) snapshot(target).get("version")).longValue() - 1);
        assertRejectedUpdate(target, stale, 409, "READY", staleVersion);
        passed("stale-account-cas", target);

        String expired = uploadAvatar(maker, originalColor);
        assertThat(jdbc.update("UPDATE nx_support_admin_avatar_asset SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=? AND uploader_id=?",
                expired, maker)).isEqualTo(1);
        assertRejectedUpdate(target, expired, 409, "READY", null);
        passed("expired-ready-asset", expired);

        String cancelled = uploadAvatar(maker, originalColor);
        JsonNode cancelledResponse = http("DELETE", ACCOUNTS + "/avatar-assets/" + cancelled, actorTokens.get(maker), null, key());
        success(cancelledResponse);
        assertThat(cancelledResponse.path("data").path("assetId").asText()).isEqualTo(cancelled);
        assertThat(cancelledResponse.path("data").path("status").asText()).isEqualTo("CANCELLED");
        assertRejectedUpdate(target, cancelled, 409, "CANCELLED", null);
        passed("cancelled-asset", cancelled);

        // The first asset remains historical ATTACHED after replacement; it is never used as a restoration shortcut.
        assertRejectedUpdate(target, staged, 409, "ATTACHED", null);
        passed("old-attached-asset-cannot-rebind", staged);

        String unavailable = uploadAvatar(maker, originalColor);
        String unavailableObject = jdbc.queryForObject("SELECT object_key FROM nx_support_admin_avatar_asset WHERE id=? AND uploader_id=?",
                String.class, unavailable, maker);
        storage.remove(unavailableObject);
        assertRejectedUpdate(target, unavailable, 503, "READY", null);
        String failedCreate = ownAccountName("storage");
        assertRejectedCreate(failedCreate, unavailable, 503, "READY");
        passed("storage-failure-rolls-back-create-and-update", unavailable);

        String mismatched = uploadAvatar(maker, originalColor);
        AuditOperationProposalRequest correct = proposal("a1_account_update_profile", profileParams(target, mismatched));
        AuditOperationProposalRequest wrongTarget = new AuditOperationProposalRequest(correct.action(), correct.obj(),
                correct.beforeValue(), correct.afterValue(), correct.operator(), correct.operatorRole(), correct.type(),
                correct.amplifies(), correct.sos(), correct.roleGate(), correct.reason(), correct.sourceDomain(),
                correct.command(), new AuditLockTarget("A", "account", String.valueOf(checker)), null);
        long ticketCount = jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_operation_ticket WHERE operator_name=?",
                Long.class, username(maker));
        Map<String, Object> originalTarget = snapshot(target);
        assertCode(http("POST", OPERATIONS, actorTokens.get(maker), wrongTarget, key()), 422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_operation_ticket WHERE operator_name=?",
                Long.class, username(maker))).isEqualTo(ticketCount);
        assertThat(snapshot(target)).isEqualTo(originalTarget);
        assertAsset(mismatched, maker, "READY", null);
        passed("proposal-target-mismatch", target);

        String tamperedTicket = propose(correct, key());
        assertThat(jdbc.update("UPDATE nx_audit_operation_ticket SET object_text=? WHERE operation_id=? AND operator_name=?",
                String.valueOf(checker), tamperedTicket, username(maker))).isEqualTo(1);
        assertCode(approve(tamperedTicket, checker, key()), 422);
        assertPending(tamperedTicket);
        assertThat(snapshot(target)).isEqualTo(originalTarget);
        assertAsset(mismatched, maker, "READY", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='A2_OPERATION_APPROVED' AND resource_id=? AND result='SUCCESS'",
                Long.class, tamperedTicket)).isZero();
        passed("persisted-ticket-target-tamper", tamperedTicket);
        withdraw(tamperedTicket);

        readImage(ACCOUNTS + "/" + target + "/avatar", actorTokens.get(checker), replacementColor);
        assertThat(snapshot(target)).isEqualTo(after);
        assertSharedStateUnchanged();
        validated = true;
    }

    private String ownAccountName(String suffix) {
        String name = avatarRun + "_" + suffix;
        assertThat(name).matches("[a-z0-9._-]{3,32}");
        assertThat(accountCount(name)).isZero();
        accountNames.add(name);
        return name;
    }

    private Map<String, Object> createParams(String username, String asset) {
        return new LinkedHashMap<>(Map.of("username", username, "displayName", "Avatar A2 acceptance",
                "email", username + "@example.invalid", "role", "support", "avatarAssetId", asset));
    }

    private Map<String, Object> profileParams(long target, String asset) throws Exception {
        JsonNode account = account(target);
        return new LinkedHashMap<>(Map.of("accountId", String.valueOf(target), "username", account.path("username").asText(),
                "displayName", account.path("name").asText(), "email", account.path("email").asText(""),
                "expectedVersion", account.path("version").asText(), "avatarAssetId", asset));
    }

    private AuditOperationProposalRequest proposal(String operation, Map<String, Object> params) {
        String target = String.valueOf(params.get("a1_account_create".equals(operation) ? "username" : "accountId"));
        return new AuditOperationProposalRequest("后台账号头像(A1)", target, "Original account avatar", "Requested avatar",
                "client-forged-operator", "超管", "acct", false, false, "超管", "Actual avatar A2 acceptance " + avatarRun,
                "A", new AuditReplayCommand("A", operation, params), new AuditLockTarget("A", "account", target), null);
    }

    private String propose(AuditOperationProposalRequest request, String command) throws Exception {
        JsonNode response = http("POST", OPERATIONS, actorTokens.get(maker), request, command);
        success(response);
        String ticket = response.path("data").path("id").asText();
        assertThat(ticket).matches("WO-[A-Z0-9-]+");
        assertPending(ticket);
        JsonNode frozen = json.readTree(jdbc.queryForObject("SELECT command_json FROM nx_audit_operation_ticket WHERE operation_id=?",
                String.class, ticket));
        assertThat(frozen.path("avatarMakerAdminId").asLong()).isEqualTo(maker);
        assertThat(jdbc.queryForObject("SELECT operator_name FROM nx_audit_operation_ticket WHERE operation_id=?",
                String.class, ticket)).isEqualTo(username(maker));
        return ticket;
    }

    private JsonNode approve(String ticket, long actor, String command) throws Exception {
        return http("POST", OPERATIONS + "/" + ticket + "/approve", actorTokens.get(actor),
                Map.of("reason", "Actual checker avatar approval " + avatarRun, "operator", "client-forged-checker"), command);
    }

    private void assertRejectedUpdate(long target, String asset, int code, String assetState, String staleVersion) throws Exception {
        Map<String, Object> before = snapshot(target);
        Map<String, Object> params = profileParams(target, asset);
        if (staleVersion != null) params.put("expectedVersion", staleVersion);
        String ticket = propose(proposal("a1_account_update_profile", params), key());
        assertCode(approve(ticket, checker, key()), code);
        assertPending(ticket);
        assertThat(snapshot(target)).as("Failed approval must roll back real account CAS and avatar state").isEqualTo(before);
        assertThat(account(target).path("avatarAssetId").asText()).isEqualTo(before.get("avatar_asset_id"));
        assertThat(account(target).path("avatarVersion").asLong()).isEqualTo(((Number) before.get("avatar_version")).longValue());
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?", String.class, asset)).isEqualTo(assetState);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='A2_OPERATION_APPROVED' AND resource_id=? AND result='SUCCESS'",
                Long.class, ticket)).isZero();
        withdraw(ticket);
    }

    private void assertRejectedCreate(String username, String asset, int code, String assetState) throws Exception {
        assertThat(accountCount(username)).isZero();
        List<Long> orphanRoleAdminsBefore = jdbc.queryForList("SELECT DISTINCT rr.admin_id FROM nx_admin_role_relation rr "
                + "LEFT JOIN nx_admin a ON a.id=rr.admin_id WHERE a.id IS NULL ORDER BY rr.admin_id", Long.class);
        List<Long> orphanStateAdminsBefore = jdbc.queryForList("SELECT DISTINCT s.admin_id FROM nx_admin_account_state s "
                + "LEFT JOIN nx_admin a ON a.id=s.admin_id WHERE a.id IS NULL ORDER BY s.admin_id", Long.class);
        String ticket = propose(proposal("a1_account_create", createParams(username, asset)), key());
        String approveKey = key();
        assertCode(fixtureActors().createApproval(username, ticket, checker, approveKey, () -> approve(ticket, checker, approveKey)), code);
        assertPending(ticket);
        assertThat(accountCount(username)).as("Actual inserted account must roll back").isZero();
        assertThat(jdbc.queryForList("SELECT DISTINCT rr.admin_id FROM nx_admin_role_relation rr "
                + "LEFT JOIN nx_admin a ON a.id=rr.admin_id WHERE a.id IS NULL ORDER BY rr.admin_id", Long.class))
                .as("Failed create must leave existing orphan role admin IDs unchanged").isEqualTo(orphanRoleAdminsBefore);
        assertThat(jdbc.queryForList("SELECT DISTINCT s.admin_id FROM nx_admin_account_state s "
                + "LEFT JOIN nx_admin a ON a.id=s.admin_id WHERE a.id IS NULL ORDER BY s.admin_id", Long.class))
                .as("Failed create must leave existing orphan account-state admin IDs unchanged").isEqualTo(orphanStateAdminsBefore);
        assertThat(jdbc.queryForObject("SELECT attached_admin_id FROM nx_support_admin_avatar_asset WHERE id=?", Long.class, asset)).isNull();
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_admin_avatar_asset WHERE id=?", String.class, asset)).isEqualTo(assetState);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='A2_OPERATION_APPROVED' "
                + "AND resource_id=? AND result='SUCCESS'", Long.class, ticket))
                .as("Failed create must not leave a successful A2 approval audit for this ticket").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action LIKE 'A1_%' AND actor_id=? "
                + "AND result='SUCCESS' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.idempotencyKey'))=?",
                Long.class, checker, approveKey))
                .as("Failed create must not leave a successful A1 audit for this checker and approval key").isZero();
        withdraw(ticket);
    }

    private void withdraw(String ticket) throws Exception {
        JsonNode response = http("POST", OPERATIONS + "/" + ticket + "/withdraw", actorTokens.get(maker),
                Map.of("reason", "Close only owned avatar acceptance ticket", "expectedStatus", "pending", "operator", "ignored"), key());
        success(response);
        assertThat(jdbc.queryForObject("SELECT status FROM nx_audit_operation_ticket WHERE operation_id=?", String.class, ticket)).isEqualTo("withdrawn");
    }

    private JsonNode account(long target) throws Exception {
        JsonNode response = http("GET", ACCOUNTS + "/overview", actorTokens.get(checker), null, null);
        success(response);
        for (JsonNode record : response.path("data").path("operators")) {
            if (record.path("id").asLong() == target) return record;
        }
        throw new AssertionError("Owned avatar test account missing from original A1 overview: " + target);
    }

    private Map<String, Object> snapshot(long target) {
        return jdbc.queryForMap("SELECT a.username,a.nickname,a.email,a.status,a.super_admin,a.version,s.avatar_asset_id,s.avatar_version "
                + "FROM nx_admin a LEFT JOIN nx_admin_account_state s ON s.admin_id=a.id AND s.is_deleted=0 WHERE a.id=?", target);
    }

    private long accountCount(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM nx_admin WHERE username=?", Long.class, username);
    }

    private String username(long actor) {
        return jdbc.queryForObject("SELECT username FROM nx_admin WHERE id=?", String.class, actor);
    }

    private void assertPending(String ticket) {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_audit_operation_ticket WHERE operation_id=?", String.class, ticket)).isEqualTo("pending");
    }

    private void assertAsset(String asset, long uploader, String state, Long attachedAdmin) {
        Map<String, Object> row = jdbc.queryForMap("SELECT uploader_id,state,attached_admin_id FROM nx_support_admin_avatar_asset WHERE id=?", asset);
        assertThat(((Number) row.get("uploader_id")).longValue()).isEqualTo(uploader);
        assertThat(row.get("state")).isEqualTo(state);
        assertThat(row.get("attached_admin_id")).isEqualTo(attachedAdmin);
    }

    private void assertApprovalActors(String ticket, long target, String action) {
        assertThat(jdbc.queryForObject("SELECT status FROM nx_audit_operation_ticket WHERE operation_id=?", String.class, ticket)).isEqualTo("approved");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action='A2_OPERATION_APPROVED' AND resource_id=? AND actor_id=? AND actor_username=? AND result='SUCCESS'",
                Long.class, ticket, checker, username(checker))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_audit_log WHERE action=? AND resource_id=? AND actor_id=? AND actor_username=? AND result='SUCCESS'",
                Long.class, action, String.valueOf(target), checker, username(checker))).isEqualTo(1L);
    }

    private String uploadAvatar(long uploader, int color) throws Exception {
        String multipart = "avatar-a2-" + UUID.randomUUID(), client = key(), command = key();
        var intent = objectRequest(SupportObjectEvidenceLedger.Kind.AVATAR,uploader,null,null,client,command,null,storageProperties.getBucket(),false);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + multipart + "\r\nContent-Disposition: form-data; name=\"clientUploadId\"\r\n\r\n" + client
                + "\r\n--" + multipart + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png(color));
        body.write(("\r\n--" + multipart + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18141" + ACCOUNTS + "/avatar-assets"))
                .timeout(Duration.ofSeconds(25)).header("Authorization", "Bearer " + actorTokens.get(uploader))
                .header("Idempotency-Key", command).header("Content-Type", "multipart/form-data; boundary=" + multipart)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> response = sendObjectRequest(intent,request);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode uploaded = json.readTree(response.body());
        success(uploaded);
        assertThat(uploaded.toString()).doesNotContain("objectKey", "bucket");
        String asset = uploaded.path("data").path("assetId").asText();
        assertThat(asset).matches("[a-f0-9-]{36}");
        assetIds.add(asset);
        assertAsset(asset, uploader, "READY", null);
        readImage(ACCOUNTS + "/avatar-assets/" + asset, actorTokens.get(uploader), color);
        return asset;
    }

    private void readImage(String path, String token, int color) throws Exception {
        HttpResponse<byte[]> response = download(path, token);
        assertThat(response.statusCode()).as("Original controlled image route %s", path).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).isEqualTo("image/png");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(response.body()));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(4);
        assertThat(image.getHeight()).isEqualTo(4);
        assertThat(image.getRGB(1, 1) & 0xFFFFFF).isEqualTo(color);
    }

    private void assertCode(JsonNode response, int code) {
        assertThat(response.has("code")).isTrue();
        assertThat(response.path("code").isIntegralNumber()).isTrue();
        assertThat(response.path("code").asInt()).as("Actual API result %s", response.path("message")).isEqualTo(code);
    }

    private void success(JsonNode response) { assertCode(response, 0); }

    @Override
    String key() { return "avatar-a2-" + UUID.randomUUID(); }

    private void passed(String check, Object resource) {
        proofs.put("avatar-a2-" + check, Map.of("status", "pass", "suite", getClass().getSimpleName(),
                "testcase", METHOD, "resource", resource));
    }

    private void assertSharedStateUnchanged() {
        assertThat(jdbc.queryForMap("SELECT * FROM nx_support_rules WHERE id=1")).isEqualTo(originalRules);
        assertThat(jdbc.queryForList("SELECT admin_id FROM nx_support_agent_profile WHERE enabled=1 AND is_deleted=0 ORDER BY admin_id",
                Long.class)).isEqualTo(originalEnabled);
    }

    @AfterEach
    void cleanOnlyOwnFixture() throws Exception {
        if (!boundaryReady) return;
        List<Throwable> failures = new ArrayList<>();
        Set<Long> ownAdmins = new LinkedHashSet<>();
        try { ownAdmins.addAll(fixtureActors().ownedIds()); } catch (Throwable failure) { failures.add(failure); }
        try {
            // Ownership comes only from the already-durable exact creation documents.
            if (maker > 0 && actorTokens.containsKey(maker)) {
                for (String ticket : jdbc.queryForList("SELECT operation_id FROM nx_audit_operation_ticket WHERE operator_name=? AND status='pending' AND is_deleted=0",
                        String.class, username(maker))) {
                    try { withdraw(ticket); } catch (Throwable failure) { failures.add(failure); }
                }
            }
            for (long uploader : List.of(maker, checker, third)) {
                if (uploader <= 0) continue;
                for (Map<String, Object> asset : jdbc.queryForList("SELECT id,state,object_key FROM nx_support_admin_avatar_asset WHERE uploader_id=?", uploader)) {
                    try {
                        String id = asset.get("id").toString();
                        if ("READY".equals(asset.get("state")) && actorTokens.containsKey(uploader)) {
                            JsonNode cancelled = http("DELETE", ACCOUNTS + "/avatar-assets/" + id, actorTokens.get(uploader), null, key());
                            success(cancelled);
                            assertThat(cancelled.path("data").path("assetId").asText()).isEqualTo(id);
                            assertThat(cancelled.path("data").path("status").asText()).isEqualTo("CANCELLED");
                        }
                        storage.remove(asset.get("object_key").toString());
                        assertThat(storage.exists(asset.get("object_key").toString())).isFalse();
                    } catch (Throwable failure) { failures.add(failure); }
                }
            }
        } catch (Throwable failure) {
            failures.add(failure);
        } finally {
            try { cleanupObjectEvidence(); } catch (Throwable failure) { failures.add(failure); }
            for (long actor : ownAdmins) {
                try {
                    fixtureActors().cleanup(actor);
                } catch (Throwable failure) { failures.add(failure); }
            }
            SecurityContextHolder.clearContext();
        }
        try { if (originalRules != null && originalEnabled != null) assertSharedStateUnchanged(); }
        catch (Throwable failure) { failures.add(failure); }
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("Owned avatar A2 fixture cleanup failed; runtime acceptance remains failed");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        if (validated) {
            Path directory = Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"));
            Files.createDirectories(directory);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("run", avatarRun);
            evidence.put("feature", "ADMIN_AVATAR_A2");
            evidence.put("capability", "runtime");
            evidence.put("checkedAt", Instant.now().toString());
            evidence.put("database", "cs_enhance_20261001");
            evidence.put("databasePort", 33329);
            evidence.put("httpPort", 18141);
            evidence.put("makerAdminId", maker);
            evidence.put("checkerAdminId", checker);
            evidence.put("checks", proofs);
            evidence.put("ownedAssetIds", assetIds);
            evidence.put("ownedAccountsDisabled", ownAdmins);
            evidence.put("actorEvidenceSchemaVersion", 3);
            evidence.put("creationProofs", ownAdmins.stream().map(id -> fixtureActors().creationReference(id)).toList());
            evidence.put("cleanup", "pass");
            evidence.put("authentication", "isolated JWT sessions with real DB roles and grants; MFA login not exercised");
            evidence.put("workflowRunId", System.getenv("WORKFLOW_RUN_ID"));
            evidence.put("snapshotHash", System.getenv("WORKFLOW_SNAPSHOT_HASH"));
            Files.writeString(directory.resolve("avatar-a2-runtime-" + avatarRun + ".json"), json.writeValueAsString(evidence));
        }
    }
}
