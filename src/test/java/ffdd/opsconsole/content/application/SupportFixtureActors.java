package ffdd.opsconsole.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only, fail-closed fixture ownership. A DB lookup or cleanup claim can never create ownership. */
final class SupportFixtureActors {
    private final SupportRuntimeTarget target;
    private static final String HISTORICAL_UNPROVEN_RUN = "932129b0-d190-478a-9124-62b3e18a95fd";
    private static final Set<Long> HISTORICAL_UNPROVEN_IDS = Set.of(4275L, 4276L, 4277L, 4278L);
    private static final String SESSION_PREFIX = "ops:admin:session:";
    private static final String INSERT = "INSERT INTO nx_admin(username,password_hash,nickname,super_admin,status) VALUES(?,?,?,?,1)";
    private static final DefaultRedisScript<Long> DELETE_OWN_SESSION = new DefaultRedisScript<>(
            "if redis.call('HGET',KEYS[1],'adminId') == ARGV[1] then return redis.call('DEL',KEYS[1]) else return -1 end", Long.class);
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    private final String run;
    private final String suite;
    private final Path directory;
    private final JsonNode context;
    private final String contextSha256;
    private final Map<String, String> environment;
    private final Map<Long, Creation> owned = new LinkedHashMap<>();
    private final Map<String, Creation> exactCommands = new LinkedHashMap<>();

    @FunctionalInterface interface ResponseCall { JsonNode execute() throws Exception; }
    private record Creation(long id, String username, String createdAt, Path path, String sha256, JsonNode body) {}
    private record Intent(String operationId, Map<String, Object> request, Path path, String sha256) {}

    SupportFixtureActors(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json,
            PlatformTransactionManager transactions, String run, String suite) {
        this(jdbc, redis, json, transactions, run, suite, System.getenv());
    }

    /** Injection is for isolated unit tests; production fixture calls use the actual process environment. */
    SupportFixtureActors(JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper json,
            PlatformTransactionManager transactions, String run, String suite, Map<String, String> environment) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.redis = Objects.requireNonNull(redis);
        this.json = Objects.requireNonNull(json);
        this.transactions = Objects.requireNonNull(transactions);
        this.run = requireText(run, "fixture run");
        this.suite = requireText(suite, "fixture suite");
        this.environment = Map.copyOf(environment);
        this.target = SupportRuntimeTarget.select(environment);
        this.directory = Path.of(requiredEnv("CS_ENHANCE_EVIDENCE_DIR")).toAbsolutePath().normalize();
        Path contextPath = Path.of(requiredEnv("CS_ENHANCE_ACTOR_CONTEXT")).toAbsolutePath().normalize();
        this.contextSha256 = requiredEnv("CS_ENHANCE_ACTOR_CONTEXT_SHA256");
        try {
            byte[] bytes = Files.readAllBytes(contextPath);
            require(sha256(bytes).equals(contextSha256), "Actor context bytes changed");
            this.context = json.readTree(bytes);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        validateContext();
    }

    private void validateContext() {
        require(context.path("schemaVersion").asInt() == (target.analytics() ? 2 : 1), "Actor context version required");
        require("BUSINESS_PHASE".equals(context.path("purpose").asText())
                && context.path("businessAuthorized").asBoolean(false), "Fresh business release context required");
        for (var key : Map.of("taskId", "WORKFLOW_TASK_ID", "stepId", "WORKFLOW_STEP_ID",
                "checkId", "WORKFLOW_CHECK_ID", "runId", "WORKFLOW_RUN_ID", "repo", "WORKFLOW_REPO",
                "snapshotHash", "WORKFLOW_SNAPSHOT_HASH").entrySet())
            require(requiredEnv(key.getValue()).equals(context.path("identity").path(key.getKey()).asText()),
                    "Actor context raw Native identity mismatch: " + key.getKey());
        require(context.path("candidate").asText().matches("[a-f0-9]{40}"), "Frozen candidate required");
        require(context.path("identity").path("snapshotHash").asText().matches("[a-f0-9]{64}"), "Native snapshot required");
        requireText(context.path("windowId").asText(), "lease window");
        if (target.analytics()) SupportExclusiveRuntimeOwnership.validate(context, target);
        else for (String field : List.of("leaseSha256", "sourceHandoffSha256", "preflightManifestSha256",
                "rootAcceptanceSha256", "businessReleaseSha256", "preCaptureContextSha256"))
            require(context.path(field).asText().matches("[a-f0-9]{64}"), "Actual authority hash missing: " + field);
        for (String field : List.of("rootSharedBefore", "phaseSharedBefore")) {
            requireText(context.path(field).path("path").asText(), field);
            require(context.path(field).path("sha256").asText().matches("[a-f0-9]{64}"), "Full before hash missing: " + field);
        }
        var resource = context.path("resourceIdentity");
        require(target.database().equals(resource.path("database").asText())
                && resource.path("databasePort").asInt() == target.databasePort()
                && "127.0.0.1".equals(resource.path("redisHost").asText())
                && resource.path("redisPort").asInt() == target.redisPort() && resource.path("redisDatabase").asInt(-1) == 0
                && target.storageEndpoint().equals(resource.path("storageEndpoint").asText())
                && target.bucket().equals(resource.path("storageBucket").asText()), "Exact actor resource required");
        beforeResource();
    }

    private void beforeResource() {
        require(Instant.now().isBefore(Instant.parse(context.path("hardDeadline").asText())), "Actor resource hard deadline reached");
    }

    private void beforeBusiness() {
        beforeResource();
        require(Instant.now().isBefore(Instant.parse(context.path("businessDeadline").asText())), "Actor creation business deadline reached");
    }

    private void resourceBoundary() {
        beforeResource();
        if (target.analytics()) SupportExclusiveRuntimeOwnership.requireActual(context, target, jdbc);
        require(target.database().equals(jdbc.queryForObject("SELECT DATABASE()", String.class)), "Actor DB mismatch");
        beforeResource();
        require(Integer.valueOf(target.databasePort()).equals(jdbc.queryForObject("SELECT @@port", Integer.class)), "Actor DB port mismatch");
        require(redis.getConnectionFactory() instanceof LettuceConnectionFactory, "Known isolated Redis connection required");
        var factory = (LettuceConnectionFactory) redis.getConnectionFactory();
        require("127.0.0.1".equals(factory.getHostName()) && factory.getPort() == target.redisPort() && factory.getDatabase() == 0,
                "Actor Redis boundary mismatch");
    }

    long createSql(String username, String passwordHash, String nickname, String role, String seat) {
        beforeBusiness();
        requireResolvedIntents();
        resourceBoundary();
        var request = map("kind", "SQL_INSERT_COMMIT", "username", username, "nickname", nickname,
                "role", role, "seat", seat, "credentialKind", credentialKind(passwordHash));
        Intent intent = begin(request);
        var result = new LinkedHashMap<String, Object>();
        try {
            var transaction = new TransactionTemplate(transactions);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Long id = transaction.execute(status -> {
                beforeBusiness();
                var key = new GeneratedKeyHolder();
                int changed = jdbc.update(connection -> {
                    var statement = connection.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
                    statement.setString(1, username); statement.setString(2, passwordHash);
                    statement.setString(3, nickname); statement.setInt(4, "SUPER_ADMIN".equals(role) ? 1 : 0);
                    return statement;
                }, key);
                require(changed == 1 && key.getKey() != null, "Exactly one inserted actor and generated key required");
                long generated = key.getKey().longValue();
                require(generated > 0, "Positive generated key required");
                result.put("statement", INSERT); result.put("affectedRows", changed); result.put("generatedKey", generated);
                result.put("connectionId", jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                result.put("databaseObservedAt", String.valueOf(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Object.class)));
                beforeBusiness();
                require(jdbc.update("INSERT INTO nx_admin_role_relation(admin_id,role_id) SELECT ?,id FROM nx_admin_role WHERE role_code=? AND is_deleted=0", generated, role) == 1,
                        "Exactly one fixture role required");
                beforeBusiness();
                require(jdbc.update("INSERT INTO nx_support_agent_profile(admin_id,seat_type,position,service_types,tags,max_concurrent,enabled,transferable,busy) VALUES(?,?,?,'support,advisor','',0,1,1,0)", generated, seat, seat) == 1,
                        "Exactly one fixture profile required");
                return generated;
            });
            require(id != null, "SQL transaction returned no actor");
            result.put("commitReturnedAt", Instant.now().toString());
            recordFixtureActor(intent, id, username, result, "COMMIT_RETURNED");
            return id;
        } catch (Throwable failure) {
            unresolved(intent, failure);
            throw propagate(failure);
        }
    }

    JsonNode createHttp(String username, String command, ResponseCall action) throws Exception {
        return responseCreate(username, command, null, 0L, action);
    }

    JsonNode createApproval(String username, String ticket, long checker, String command, ResponseCall action) throws Exception {
        return responseCreate(username, command, requireText(ticket, "A2 ticket"), checker, action);
    }

    private JsonNode responseCreate(String username, String command, String ticket, long checker, ResponseCall action) throws Exception {
        beforeBusiness();
        requireResolvedIntents();
        resourceBoundary();
        requireText(command, "exact create command");
        var request = map("kind", ticket == null ? "HTTP_CREATE_RESPONSE" : "A2_APPROVAL_RESPONSE", "username", username,
                "idempotencyKey", command, "ticketId", ticket, "checkerAdminId", checker);
        Intent intent = begin(request);
        try {
            beforeBusiness();
            JsonNode response = Objects.requireNonNull(action.execute(), "Exact create response required");
            require(response.has("code") && response.path("code").canConvertToInt(), "Explicit response code required");
            String bodyUtf8 = json.writeValueAsString(response);
            var responseProof = map("body", response, "bodyUtf8", bodyUtf8, "bodySha256", sha256(bodyUtf8.getBytes(StandardCharsets.UTF_8)),
                    "receivedAt", Instant.now().toString());
            if (response.path("code").asInt() != 0) {
                // A rejection is never ownership. An unexpected commit is unresolved, not adopted by name.
                beforeResource();
                String noAccountSql = "SELECT COUNT(*) FROM nx_admin WHERE username=?";
                Long accountCount = jdbc.queryForObject(noAccountSql, Long.class, username);
                require(Long.valueOf(0).equals(accountCount),
                        "Rejected create left an account: ownership remains unresolved");
                beforeResource();
                var matchingAudits = creationAudits(command, checker);
                require(matchingAudits.isEmpty(), "Rejected create left a committed creation audit");
                terminal(intent, "NOT_CREATED", map("response", responseProof, "successfulExactResponse", false, "ownsActor", false,
                        "rejectionReadback", map("matchingAccountCount", accountCount, "matchingCreationAuditCount", matchingAudits.size(),
                                "observedAt", Instant.now().toString(), "accountQuery", noAccountSql, "accountParameters", List.of(username),
                                "auditQuery", creationAuditSql(checker), "auditParameters", checker > 0 ? List.of(checker, command) : List.of(command))));
                return response;
            }
            beforeResource();
            List<Map<String, Object>> audits = creationAudits(command, checker);
            require(audits.size() == 1, "Exact successful creation audit required");
            long id = Long.parseLong(String.valueOf(audits.get(0).get("resource_id")));
            if (ticket == null) require(response.path("data").path("id").asLong(-1) == id, "HTTP response actor ID must match creation audit");
            else {
                require(ticket.equals(response.path("data").path("id").asText())
                        && "approved".equals(response.path("data").path("status").asText()), "Exact successful A2 approval response required");
                beforeResource();
                var tickets = jdbc.queryForList("SELECT operation_id,status,command_json,operator_name,decided_at FROM nx_audit_operation_ticket WHERE operation_id=?", ticket);
                require(tickets.size() == 1 && "approved".equals(tickets.get(0).get("status")), "Committed approved A2 ticket required");
                var stored = json.readTree(String.valueOf(tickets.get(0).get("command_json")));
                require("a1_account_create".equals(stored.path("op").asText())
                        && username.equals(stored.path("params").path("username").asText()), "A2 ticket exact create command required");
                beforeResource();
                var decisions = jdbc.queryForList("SELECT id,resource_id,actor_id,detail_json,created_at FROM nx_audit_log WHERE action='A2_OPERATION_APPROVED' AND resource_id=? AND actor_id=? AND result='SUCCESS' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.idempotencyKey'))=?", ticket, checker, command);
                require(decisions.size() == 1, "Exact A2 decision audit required");
                responseProof.put("approvedTicket", tickets.get(0)); responseProof.put("decisionAudit", decisions.get(0));
            }
            responseProof.put("creationAudit", audits.get(0));
            Creation replay = exactCommands.get(command);
            if (replay != null) {
                require(replay.id() == id, "Replay returned another actor");
                verifyCreation(replay);
                require(replay.body().path("create").path("request").equals(json.valueToTree(request))
                        && replay.body().path("create").path("response").path("body").equals(response),
                        "Replay changed its exact original request or successful response");
                terminal(intent, "CREATE_REPLAY", map("creationProof", reference(replay), "response", responseProof, "ownsActor", false));
            } else {
                Creation created = recordFixtureActor(intent, id, username, responseProof, "SUCCESSFUL_HTTP_RESPONSE_AND_COMMITTED_AUDIT");
                exactCommands.put(command, created);
            }
            return response;
        } catch (Throwable failure) {
            unresolved(intent, failure);
            if (failure instanceof Exception exception) throw exception;
            throw (Error) failure;
        }
    }

    private List<Map<String, Object>> creationAudits(String command, long checker) {
        beforeResource();
        return checker > 0 ? jdbc.queryForList(creationAuditSql(checker), checker, command) : jdbc.queryForList(creationAuditSql(checker), command);
    }

    private static String creationAuditSql(long checker) {
        return "SELECT id,resource_id,actor_id,detail_json,created_at FROM nx_audit_log WHERE action='A1_OPERATOR_CREATED' AND resource_type='A1_ADMIN_ACCOUNT' "
                + (checker > 0 ? "AND actor_id=? " : "")
                + "AND result='SUCCESS' AND JSON_UNQUOTE(JSON_EXTRACT(detail_json,'$.idempotencyKey'))=?";
    }

    private Intent begin(Map<String, Object> request) {
        beforeBusiness();
        String operation = UUID.randomUUID().toString();
        Map<String, Object> body = event("CREATE_INTENT", operation);
        body.put("request", request); body.put("ownsActor", false);
        Path path = directory.resolve("fixture-actor-intents").resolve(operation + ".json");
        return new Intent(operation, request, path, persist(path, body));
    }

    private Creation recordFixtureActor(Intent intent, long id, String username, Map<String, Object> response, String outcome) {
        require(!(HISTORICAL_UNPROVEN_RUN.equals(context.path("identity").path("runId").asText())
                && HISTORICAL_UNPROVEN_IDS.contains(id)), "Historical R12 A2 IDs remain UNPROVEN");
        require(!owned.containsKey(id), "Actor already has creator evidence");
        beforeResource();
        var row = jdbc.queryForMap("SELECT id,username,nickname,created_at,super_admin,status,is_deleted,CASE WHEN password_hash='fixture-disabled-password' THEN 'placeholder' WHEN password_hash='NO_LOGIN' THEN 'no-login' WHEN password_hash LIKE '$2%' THEN 'bcrypt' ELSE 'other' END credential_kind FROM nx_admin WHERE id=?", id);
        require(username.equals(row.get("username")) && ((Number) row.get("id")).longValue() == id, "Exact committed actor readback required");
        String createdAt = requireText(String.valueOf(row.get("created_at")), "actor creation time");
        var body = event("CREATED", intent.operationId());
        body.put("adminId", id); body.put("originalUsername", username); body.put("ownsActor", true);
        body.put("createdAt", createdAt); body.put("nickname", row.get("nickname")); body.put("credentialKind", row.get("credential_kind"));
        beforeResource();
        body.put("roleCodes", jdbc.queryForList("SELECT r.role_code FROM nx_admin_role_relation ar JOIN nx_admin_role r ON r.id=ar.role_id WHERE ar.admin_id=? AND ar.is_deleted=0 AND r.is_deleted=0 ORDER BY r.role_code", String.class, id));
        beforeResource();
        body.put("profiles", jdbc.queryForList("SELECT * FROM nx_support_agent_profile WHERE admin_id=? ORDER BY admin_id", id));
        body.put("intent", map("path", intent.path().toString(), "sha256", intent.sha256()));
        body.put("actor", map("id", id, "username", username, "createdAt", createdAt, "nickname", row.get("nickname"),
                "credentialKind", row.get("credential_kind"), "status", row.get("status"), "isDeleted", row.get("is_deleted")));
        body.put("create", map("kind", intent.request().get("kind"), "request", intent.request(), "response", response,
                "successfulExactResponse", true, "transactionOutcome", outcome, "committedReadback", row));
        // Only the immutable, forced and byte-read-verified exact creator document permits ownership.
        Path path = directory.resolve("fixture-actors").resolve(intent.operationId() + "-" + id + ".json");
        String hash = persist(path, body);
        Creation proof = new Creation(id, username, createdAt, path, hash, json.valueToTree(body));
        verifyCreation(proof);
        owned.put(id, proof);
        return proof;
    }

    private void terminal(Intent intent, String type, Map<String, Object> detail) {
        var body = event(type, intent.operationId());
        body.put("intent", map("path", intent.path().toString(), "sha256", intent.sha256()));
        body.putAll(detail);
        persist(directory.resolve("fixture-actor-outcomes").resolve(intent.operationId() + ".json"), body);
    }

    private void unresolved(Intent intent, Throwable failure) {
        try {
            terminal(intent, "CREATE_UNRESOLVED", map("ownsActor", false, "errorType", failure.getClass().getName(),
                    "error", String.valueOf(failure.getMessage())));
        } catch (Throwable evidenceFailure) { failure.addSuppressed(evidenceFailure); }
    }

    Set<Long> ownedIds() { return Set.copyOf(owned.keySet()); }

    void assertBusinessEntry() { beforeBusiness(); requireResolvedIntents(); }

    long actorForCommand(String command) {
        Creation creation = exactCommands.get(command);
        require(creation != null, "No durable exact creator for command");
        verifyCreation(creation);
        return creation.id();
    }

    private void requireResolvedIntents() {
        Path intents = directory.resolve("fixture-actor-intents");
        try {
            Path cleanupDirectory = directory.resolve("fixture-actor-cleanup");
            if (directoryPresent(cleanupDirectory)) try (var cleanups = Files.list(cleanupDirectory)) {
                for (Path path : cleanups.toList()) {
                    require(path.getFileName().toString().endsWith(".json"), "Incomplete cleanup artifact blocks new business");
                    JsonNode cleanup = json.readTree(Files.readAllBytes(path));
                    require("CLEANUP".equals(cleanup.path("event").asText()) && "pass".equals(cleanup.path("verdict").asText()),
                            "A previous actual cleanup failure blocks new business");
                }
            }
            if (!directoryPresent(intents)) return;
            try (var entries = Files.list(intents)) {
            for (Path path : entries.toList()) {
                require(path.getFileName().toString().endsWith(".json"), "Unknown actor intent artifact");
                JsonNode intent = json.readTree(Files.readAllBytes(path));
                require(intent.path("schemaVersion").asInt() == 3 && "CREATE_INTENT".equals(intent.path("event").asText())
                        && contextSha256.equals(intent.path("contextSha256").asText()), "Unverifiable actor intent blocks new business");
                String operation = intent.path("operationId").asText();
                require(operation.matches("[a-f0-9-]{36}") && path.getFileName().toString().equals(operation + ".json"), "Exact actor intent name required");
                var resolutions = new ArrayList<JsonNode>();
                Path actors = directory.resolve("fixture-actors");
                if (directoryPresent(actors)) try (var proofs = Files.list(actors)) {
                    for (Path candidate : proofs.toList()) {
                        require(candidate.getFileName().toString().endsWith(".json"), "Incomplete creator artifact blocks new business");
                        JsonNode proof = json.readTree(Files.readAllBytes(candidate));
                        require(proof.path("schemaVersion").asInt() == 3 && "CREATED".equals(proof.path("event").asText())
                                && candidate.getFileName().toString().equals(proof.path("operationId").asText() + "-" + proof.path("adminId").asText() + ".json"),
                                "Exact canonical creator artifact required");
                        if (operation.equals(proof.path("operationId").asText())) resolutions.add(proof);
                    }
                }
                Path outcomes = directory.resolve("fixture-actor-outcomes"),outcome = outcomes.resolve(operation + ".json");
                if (directoryPresent(outcomes)&&evidencePresent(outcome)) { plainPath(outcome,false); resolutions.add(json.readTree(Files.readAllBytes(outcome))); }
                require(resolutions.size() == 1, "Missing or contradictory actor outcome blocks new business");
                JsonNode resolved = resolutions.get(0);
                require(Set.of("CREATED", "NOT_CREATED", "CREATE_REPLAY").contains(resolved.path("event").asText())
                        && contextSha256.equals(resolved.path("contextSha256").asText())
                        && sha256(Files.readAllBytes(path)).equals(resolved.path("intent").path("sha256").asText()),
                        "Unresolved or mismatched actor outcome blocks new business");
            }
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    Map<String, Object> creationReference(long id) {
        Creation creation = requireOwned(id);
        verifyCreation(creation);
        return reference(creation);
    }

    void deferCleanup(long id, String consumerSuite) {
        require("SupportBulkRestartRuntimeTest".equals(consumerSuite), "Only the exact second JVM consumer may retain an actor");
        Creation creation = requireOwned(id);
        verifyCreation(creation);
        var body = event("DEFERRED_CLEANUP", UUID.randomUUID().toString());
        body.put("adminId", id); body.put("ownsActor", false); body.put("creationProof", reference(creation));
        body.put("consumerSuite", consumerSuite);
        persist(directory.resolve("fixture-actor-handoffs").resolve(body.get("operationId") + ".json"), body);
    }

    void importCreation(long id, JsonNode reference) {
        require("SupportBulkRestartRuntimeTest".equals(suite), "Creator import is restricted to the explicit second JVM consumer");
        try {
            Path path = Path.of(reference.path("path").asText()).toAbsolutePath().normalize();
            require(path.startsWith(directory.resolve("fixture-actors")), "Creator import must stay in current evidence directory");
            byte[] bytes = Files.readAllBytes(path);
            String hash = reference.path("sha256").asText();
            require(sha256(bytes).equals(hash), "Imported creator bytes changed");
            JsonNode body = json.readTree(bytes);
            var proof = new Creation(id, body.path("actor").path("username").asText(), body.path("actor").path("createdAt").asText(), path, hash, body);
            verifyCreation(proof);
            int matchedHandoffs = 0;
            try (var handoffs = Files.list(directory.resolve("fixture-actor-handoffs"))) {
                for (Path handoffPath : handoffs.toList()) {
                    var handoff = json.readTree(Files.readAllBytes(handoffPath));
                    if (handoff.path("adminId").asLong(-1) != id) continue;
                    require(handoff.path("schemaVersion").asInt() == 3 && "DEFERRED_CLEANUP".equals(handoff.path("event").asText())
                            && suite.equals(handoff.path("consumerSuite").asText()) && contextSha256.equals(handoff.path("contextSha256").asText())
                            && hash.equals(handoff.path("creationProof").path("sha256").asText())
                            && path.toString().equals(handoff.path("creationProof").path("path").asText()), "Exact deferred creator handoff required");
                    matchedHandoffs++;
                }
            }
            require(matchedHandoffs == 1, "Exactly one authorized deferred actor handoff required");
            require(!owned.containsKey(id), "Creator already imported");
            owned.put(id, proof);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private Creation requireOwned(long id) {
        Creation result = owned.get(id);
        require(result != null, "No durable exact creator evidence for actor " + id);
        return result;
    }

    private void verifyCreation(Creation creation) {
        try {
            plainPath(creation.path(),false);
            byte[] bytes = Files.readAllBytes(creation.path());
            require(sha256(bytes).equals(creation.sha256()), "Creator proof bytes changed");
            var body = json.readTree(bytes);
            requireUniqueCreation(creation.path(), body);
            require(body.path("schemaVersion").asInt() == 3 && "CREATED".equals(body.path("event").asText())
                    && body.path("ownsActor").asBoolean(false) && body.path("adminId").asLong(-1) == creation.id()
                    && body.path("actor").path("id").asLong(-1) == creation.id()
                    && creation.username().equals(body.path("actor").path("username").asText())
                    && creation.createdAt().equals(body.path("actor").path("createdAt").asText())
                    && contextSha256.equals(body.path("contextSha256").asText()), "Exact current creator proof required");
            for (String field : List.of("identity", "candidate", "windowId", "resourceIdentity", "businessDeadline", "hardDeadline", "leaseSha256", "businessReleaseSha256", "ownershipMode", "resourceOwnership"))
                require(context.path(field).equals(body.path(field)), "Creator context mismatch: " + field);
            require(body.path("create").path("successfulExactResponse").asBoolean(false), "Exact successful create evidence required");
            require(body.path("create").path("committedReadback").path("id").asLong(-1) == creation.id()
                    && creation.username().equals(body.path("create").path("committedReadback").path("username").asText()), "Creator exact committed readback required");
            String kind = body.path("create").path("kind").asText();
            require(Set.of("SQL_INSERT_COMMIT", "HTTP_CREATE_RESPONSE", "A2_APPROVAL_RESPONSE").contains(kind), "Known exact creation mechanism required");
            if ("SQL_INSERT_COMMIT".equals(kind)) {
                require(body.path("create").path("response").path("generatedKey").asLong(-1) == creation.id()
                        && body.path("create").path("response").path("affectedRows").asInt(-1) == 1
                        && "COMMIT_RETURNED".equals(body.path("create").path("transactionOutcome").asText()), "Exact returned SQL creation required");
            }
            Path intentPath = Path.of(body.path("intent").path("path").asText()).toAbsolutePath().normalize();
            require(intentPath.equals(directory.resolve("fixture-actor-intents").resolve(body.path("operationId").asText()+".json").toAbsolutePath().normalize()), "Current exact canonical creation intent path required");
            byte[] intentBytes = Files.readAllBytes(intentPath);
            require(sha256(intentBytes).equals(body.path("intent").path("sha256").asText()), "Creator intent bytes changed");
            JsonNode intent = json.readTree(intentBytes);
            require("CREATE_INTENT".equals(intent.path("event").asText()) && contextSha256.equals(intent.path("contextSha256").asText())
                    && intent.path("operationId").equals(body.path("operationId")) && intent.path("request").equals(body.path("create").path("request")), "Creator intent does not prove this exact creation request");
            Path outcomePath = directory.resolve("fixture-actor-outcomes").resolve(body.path("operationId").asText() + ".json");
            require(!evidencePresent(outcomePath), "Creator has a contradictory or unresolved outcome");
            require(!(HISTORICAL_UNPROVEN_RUN.equals(body.path("runId").asText()) && HISTORICAL_UNPROVEN_IDS.contains(creation.id())),
                    "Historical R12 A2 IDs remain UNPROVEN");
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    static void plainPath(Path path, boolean directory) throws IOException {
        Path absolute=path.toAbsolutePath().normalize();
        var attributes=Files.readAttributes(absolute,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
        require(!attributes.isSymbolicLink()&&!attributes.isOther()&&(directory?attributes.isDirectory():attributes.isRegularFile())
                &&absolute.toRealPath().equals(absolute),"Plain non-reparse evidence path required");
    }

    static boolean evidencePresent(Path path) throws IOException {
        try { Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS); }
        catch (java.nio.file.NoSuchFileException absent) { return false; }
        return true;
    }

    static boolean directoryPresent(Path path) throws IOException {
        if (!evidencePresent(path)) return false;
        plainPath(path,true); return true;
    }

    private static void creatorDirectories(Path phase) throws IOException {
        plainPath(phase,true);
        for(String name:List.of("fixture-actors","fixture-actor-intents","fixture-actor-outcomes")){
            Path folder=phase.resolve(name);if(!directoryPresent(folder))continue;
            try(var files=Files.list(folder)){files.toList();}
        }
    }

    static String namedOperation(Path path) {
        var matcher=java.util.regex.Pattern.compile("^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:[.-].*)$")
                .matcher(path.getFileName().toString().toLowerCase(java.util.Locale.ROOT));
        return matcher.matches()?matcher.group(1):"";
    }

    static String namedActorId(Path path) {
        var matcher=java.util.regex.Pattern.compile("-([0-9]+)\\.json(?:\\.|$)").matcher(path.getFileName().toString());
        return matcher.find()?new java.math.BigInteger(matcher.group(1)).toString():"";
    }

    private JsonNode tryEvidenceBody(Path path) {
        try { plainPath(path,false); return json.readTree(Files.readAllBytes(path)); }
        catch (IOException|RuntimeException unreadable) { return null; }
    }

    private void requireUniqueCreation(Path selected, JsonNode body) throws IOException {
        String operation=body.path("operationId").asText(),actorId=body.path("adminId").asText();
        require(operation.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")&&actorId.matches("[1-9][0-9]*"),"Exact creation operation and ID required");
        Path creators=directory.resolve("fixture-actors");
        require(selected.toAbsolutePath().normalize().getParent().equals(creators.toAbsolutePath().normalize())
                &&selected.getFileName().toString().equals(operation+"-"+actorId+".json"),"Canonical exact creator path required");
        creatorDirectories(directory);
        try(var files=Files.list(creators)){for(Path candidate:files.toList()){
            JsonNode other=tryEvidenceBody(candidate);
            boolean claims=operation.equals(namedOperation(candidate))||actorId.equals(namedActorId(candidate));
            if(other!=null)claims=claims||operation.equals(other.path("operationId").asText())||actorId.equals(other.path("adminId").asText())
                    ||actorId.equals(other.path("actor").path("id").asText())||actorId.equals(other.path("create").path("committedReadback").path("id").asText());
            if(claims)require(candidate.toAbsolutePath().normalize().equals(selected.toAbsolutePath().normalize())&&other!=null&&other.equals(body),"Ambiguous creation operation or ID; no ownership");
        }}
        Path originalIntent=directory.resolve("fixture-actor-intents").resolve(operation+".json").toAbsolutePath().normalize();
        for(String folder:List.of("fixture-actor-outcomes","fixture-actor-intents")){
            Path dir=directory.resolve(folder);if(!directoryPresent(dir))continue;
            try(var files=Files.list(dir)){for(Path candidate:files.toList()){
                JsonNode other=tryEvidenceBody(candidate);
                boolean claims=operation.equals(namedOperation(candidate))||(other!=null&&operation.equals(other.path("operationId").asText()));
                if(claims)require(folder.equals("fixture-actor-intents")&&candidate.toAbsolutePath().normalize().equals(originalIntent)
                        &&other!=null&&other.path("event").asText().equals("CREATE_INTENT")&&other.path("operationId").asText().equals(operation),"Contradictory terminal or duplicate/partial intent; no ownership");
            }}
        }
    }

    void cleanupAll(Set<Long> retained) {
        var errors = new ArrayList<Throwable>();
        for (long id : ownedIds()) if (!retained.contains(id)) {
            try { cleanup(id); } catch (Throwable failure) { errors.add(failure); }
        }
        if (!errors.isEmpty()) {
            AssertionError failure = new AssertionError("Fixture cleanup failed; actual failures remain open");
            errors.forEach(failure::addSuppressed); throw failure;
        }
    }

    void cleanup(long id) {
        Creation creation = requireOwned(id);
        var evidence = event("CLEANUP", UUID.randomUUID().toString());
        evidence.put("adminId", id); evidence.put("ownsActor", false); evidence.put("creationProof", reference(creation));
        var failures = new ArrayList<Throwable>();
        var errors = new ArrayList<Map<String, Object>>();
        evidence.put("verdict", "fail"); evidence.put("errors", errors);
        boolean safeToMutate = false;
        try {
            verifyCreation(creation); resourceBoundary();
            beforeResource();
            var actor = jdbc.queryForMap("SELECT id,created_at FROM nx_admin WHERE id=?", id);
            require(((Number) actor.get("id")).longValue() == id && creation.createdAt().equals(String.valueOf(actor.get("created_at"))),
                    "Current actor no longer matches its exact creation");
            safeToMutate = true;
        } catch (Throwable failure) { capture(failures, errors, "ownership-and-current-row", failure); }
        String indexKey = "ops:admin:sessions:" + id, cacheKey = "rbac:v2:admin:perms:" + id;
        if (safeToMutate) {
            attempt(failures, errors, "disable-profiles", () -> { beforeResource(); jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?", id); });
            attempt(failures, errors, "disable-account", () -> { beforeResource(); jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?", id); });
            attempt(failures, errors, "revoke-exact-session-hashes", () -> {
                for (String key : scanSessions(id).matches()) {
                    beforeResource();
                    require(Long.valueOf(1).equals(redis.execute(DELETE_OWN_SESSION, List.of(key), String.valueOf(id))),
                            "Session changed owner or vanished before exact deletion: " + key);
                }
            });
            attempt(failures, errors, "delete-exact-session-index", () -> { beforeResource(); redis.delete(indexKey); });
            attempt(failures, errors, "evict-exact-permission-cache", () -> { beforeResource(); redis.delete(cacheKey); });
            attempt(failures, errors, "sql-postcondition", () -> {
                beforeResource();
                var account = jdbc.queryForMap("SELECT id,username,status,is_deleted,created_at FROM nx_admin WHERE id=?", id);
                beforeResource();
                var profiles = jdbc.queryForList("SELECT * FROM nx_support_agent_profile WHERE admin_id=? ORDER BY admin_id", id);
                evidence.put("actualSql", map("account", account, "profiles", profiles));
                require(((Number) account.get("status")).intValue() == 0
                        && creation.createdAt().equals(String.valueOf(account.get("created_at"))), "Account SQL cleanup postcondition failed");
                for (var profile : profiles) require(((Number) profile.get("enabled")).intValue() == 0, "Profile remains enabled");
            });
            attempt(failures, errors, "redis-postcondition", () -> {
                beforeResource(); Boolean index = redis.hasKey(indexKey);
                beforeResource(); var members = redis.opsForSet().members(indexKey);
                beforeResource(); Boolean cache = redis.hasKey(cacheKey);
                SessionScan scan = scanSessions(id);
                evidence.put("actualRedis", map("indexKey", indexKey, "indexExists", index, "indexMembers", members,
                        "cacheKey", cacheKey, "cacheExists", cache, "sessionNamespace", SESSION_PREFIX + "*",
                        "sessionNamespaceScanComplete", true, "sessionKeysScanned", scan.scanned().size(), "sessionKeys", scan.scanned(),
                        "sessionOwnerReadbacks", scan.owners(), "sessionMatches", scan.matches()));
                require(Boolean.FALSE.equals(index) && members != null && members.isEmpty() && Boolean.FALSE.equals(cache)
                        && scan.matches().isEmpty(), "Redis cleanup postcondition failed");
            });
        }
        evidence.put("completedAt", Instant.now().toString());
        attempt(failures, errors, "cleanup-completion-deadline", this::beforeResource);
        if (failures.isEmpty()) evidence.put("verdict", "pass");
        try {
            persist(directory.resolve("fixture-actor-cleanup").resolve(creation.body().path("operationId").asText()
                    + "-" + id + "-" + evidence.get("operationId") + ".json"), evidence);
        } catch (Throwable failure) { failures.add(failure); }
        if (!failures.isEmpty()) {
            AssertionError failure = new AssertionError("Owned actor " + id + " cleanup failed; no success substitution");
            failures.forEach(failure::addSuppressed); throw failure;
        }
    }

    private record SessionScan(List<String> scanned, List<String> matches, List<Map<String,Object>> owners) {}
    private SessionScan scanSessions(long id) {
        beforeResource();
        var scanned = new LinkedHashSet<String>();
        var matches = new LinkedHashSet<String>();
        var owners = new LinkedHashMap<String, Map<String,Object>>();
        // This namespace traversal is unconditional; an absent/empty index is never a shortcut.
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(SESSION_PREFIX + "*").count(200).build())) {
            while (cursor.hasNext()) {
                beforeResource();
                String key = cursor.next(); scanned.add(key);
                Object owner = redis.opsForHash().get(key, "adminId");
                owners.put(key, map("key", key, "adminIdObserved", owner));
                if (String.valueOf(id).equals(owner)) matches.add(key);
            }
        }
        return new SessionScan(List.copyOf(scanned), List.copyOf(matches), List.copyOf(owners.values()));
    }

    private Map<String, Object> event(String type, String operation) {
        var body = new LinkedHashMap<String, Object>();
        body.put("schemaVersion", 3); body.put("event", type); body.put("operationId", operation); body.put("at", Instant.now().toString());
        body.put("contextSha256", contextSha256); body.put("owner", target.owner()); body.put("run", run); body.put("suite", suite);
        for (String field : List.of("identity", "candidate", "windowId", "resourceIdentity", "businessDeadline", "hardDeadline", "leaseSha256",
                "sourceHandoffSha256", "preflightManifestSha256", "rootAcceptanceSha256", "businessReleaseSha256", "preCaptureContextSha256",
                "rootSharedBefore", "phaseSharedBefore", "ownershipMode", "resourceOwnership")) if (context.has(field)) body.put(field, context.path(field));
        body.put("runId", context.path("identity").path("runId").asText());
        body.put("snapshotHash", context.path("identity").path("snapshotHash").asText());
        body.put("WORKFLOW_RUN_ID", context.path("identity").path("runId").asText());
        body.put("WORKFLOW_SNAPSHOT_HASH", context.path("identity").path("snapshotHash").asText());
        body.put("WORKFLOW_STEP_ID", context.path("identity").path("stepId").asText());
        return body;
    }

    private String persist(Path path, Object body) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = json.writeValueAsBytes(body);
            require(!Files.exists(path), "Immutable evidence target already exists");
            Path pending = path.resolveSibling(path.getFileName() + ".pending-" + UUID.randomUUID());
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // A forced pending file is promoted atomically; a failed force can never leave a CREATED target.
            Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE);
            require(Arrays.equals(bytes, Files.readAllBytes(path)), "Durable evidence byte readback mismatch");
            return sha256(bytes);
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static Map<String, Object> reference(Creation creation) { return map("path", creation.path().toString(), "sha256", creation.sha256()); }
    private static void attempt(List<Throwable> failures, List<Map<String, Object>> errors, String phase, Runnable operation) {
        try { operation.run(); } catch (Throwable failure) { capture(failures, errors, phase, failure); }
    }
    private static void capture(List<Throwable> failures, List<Map<String, Object>> errors, String phase, Throwable failure) {
        failures.add(failure); errors.add(map("phase", phase, "type", failure.getClass().getName(), "message", String.valueOf(failure.getMessage())));
    }
    private static Map<String, Object> map(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
    private static String credentialKind(String value) { return "fixture-disabled-password".equals(value) ? "placeholder" : "NO_LOGIN".equals(value) ? "no-login" : value.startsWith("$2") ? "bcrypt" : "other"; }
    private String requiredEnv(String name) { return requireText(environment.get(name), name); }
    private static String requireText(String value, String field) { require(value != null && !value.isBlank(), field + " required"); return value; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static RuntimeException propagate(Throwable failure) { if (failure instanceof Error error) throw error; return failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure); }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
