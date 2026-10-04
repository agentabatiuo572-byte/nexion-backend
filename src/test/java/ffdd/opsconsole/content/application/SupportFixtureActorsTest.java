package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Isolated mock/unit regression source. Prepared in SR5; no JVM execution or resource evidence is claimed. */
class SupportFixtureActorsTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private PlatformTransactionManager transactions;
    private Map<String,String> environment;
    private final Timestamp createdAt = Timestamp.valueOf("2026-01-01 00:00:00.123456");

    @BeforeEach void mockBoundary() throws Exception {
        jdbc = mock(JdbcTemplate.class); redis = mock(StringRedisTemplate.class); transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject("SELECT DATABASE()", String.class)).thenReturn("cs_enhance_20261001");
        when(jdbc.queryForObject("SELECT @@port", Integer.class)).thenReturn(33329);
        when(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)).thenReturn(123L);
        when(jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", Object.class)).thenReturn(createdAt);
        var factory = mock(LettuceConnectionFactory.class);
        when(redis.getConnectionFactory()).thenReturn(factory); when(factory.getHostName()).thenReturn("127.0.0.1");
        when(factory.getPort()).thenReturn(16341); when(factory.getDatabase()).thenReturn(0);
        doAnswer(call -> {call.<KeyHolder>getArgument(1).getKeyList().add(Map.of("id", 100L)); return 1;})
                .when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        when(jdbc.update(startsWith("INSERT INTO nx_admin_role_relation"), eq(100L), eq("SUPPORT"))).thenReturn(1);
        when(jdbc.update(startsWith("INSERT INTO nx_support_agent_profile"), eq(100L), eq("DEDICATED"), eq("DEDICATED"))).thenReturn(1);
        when(jdbc.queryForMap(startsWith("SELECT id,username,nickname,created_at"), eq(100L))).thenReturn(
                Map.of("id",100L,"username","isolated_actor","nickname","isolated","created_at",createdAt,
                        "super_admin",0,"status",1,"is_deleted",0,"credential_kind","no-login"));
        var identity = Map.of("taskId","offline-unit","stepId","unit","checkId","unit","runId","offline-unit-run",
                "repo","D:/offline-source-only","snapshotHash","a".repeat(64));
        var context = new LinkedHashMap<String,Object>(); context.put("schemaVersion",1); context.put("purpose","BUSINESS_PHASE"); context.put("businessAuthorized",true);
        context.put("identity",identity); context.put("candidate","a".repeat(40)); context.put("windowId","offline-unit-window");
        context.put("businessDeadline","2099-01-01T00:00:00Z"); context.put("hardDeadline","2099-01-01T00:00:00Z");
        context.put("resourceIdentity",Map.of("database","cs_enhance_20261001","databasePort",33329,"redisHost","127.0.0.1","redisPort",16341,"redisDatabase",0,"storageEndpoint","http://127.0.0.1:19041","storageBucket","cs-enhance-20261001-private"));
        for(String field:List.of("leaseSha256","sourceHandoffSha256","preflightManifestSha256","rootAcceptanceSha256","businessReleaseSha256","preCaptureContextSha256"))context.put(field,"b".repeat(64));
        for(String field:List.of("rootSharedBefore","phaseSharedBefore"))context.put(field,Map.of("path","offline-before.json","sha256","c".repeat(64)));
        Path contextPath=temporary.resolve("context.json"); byte[] contextBytes=json.writeValueAsBytes(context); Files.write(contextPath,contextBytes);
        environment=new LinkedHashMap<>();environment.put("CS_ENHANCE_ACTOR_CONTEXT",contextPath.toString());
        environment.put("CS_ENHANCE_ACTOR_CONTEXT_SHA256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contextBytes)));
        environment.put("CS_ENHANCE_EVIDENCE_DIR",temporary.resolve("evidence").toString());environment.put("CS_ENHANCE_CORE_ENABLED","false");
        for(var entry:Map.of("taskId","WORKFLOW_TASK_ID","stepId","WORKFLOW_STEP_ID","checkId","WORKFLOW_CHECK_ID","runId","WORKFLOW_RUN_ID","repo","WORKFLOW_REPO","snapshotHash","WORKFLOW_SNAPSHOT_HASH").entrySet())environment.put(entry.getValue(),identity.get(entry.getKey()));
    }

    private SupportFixtureActors actors() {return new SupportFixtureActors(jdbc,redis,json,transactions,"offline-unit-fixture","MockFixtureSuite",environment);}
    private long create(SupportFixtureActors actors) {return actors.createSql("isolated_actor","NO_LOGIN","isolated","SUPPORT","DEDICATED");}
    private Path evidence(String child) {return Path.of(environment.get("CS_ENHANCE_EVIDENCE_DIR"),child);}
    private com.fasterxml.jackson.databind.JsonNode only(String child) throws Exception {try(var files=Files.list(evidence(child))){var rows=files.toList();assertThat(rows).hasSize(1);return json.readTree(Files.readAllBytes(rows.get(0)));}}

    @Test void coreFlagFalseStillRecordsActualGeneratedKeyBeforeOwnership() throws Exception {
        var actors=actors();assertThat(create(actors)).isEqualTo(100L);
        var proof=only("fixture-actors");assertThat(proof.path("event").asText()).isEqualTo("CREATED");
        assertThat(proof.path("create").path("response").path("generatedKey").asLong()).isEqualTo(100L);
        assertThat(proof.path("create").path("transactionOutcome").asText()).isEqualTo("COMMIT_RETURNED");
        assertThat(actors.ownedIds()).containsExactly(100L);verify(transactions).commit(any());
    }

    @Test void failedDurableCreatorWriteNeverClaimsCommittedActor() throws Exception {
        Files.createDirectories(evidence(""));Files.writeString(evidence("fixture-actors"),"Deliberately blocks only the creator directory");
        var actors=actors();assertThatThrownBy(()->create(actors)).isInstanceOf(RuntimeException.class);
        assertThat(actors.ownedIds()).isEmpty();assertThat(only("fixture-actor-outcomes").path("event").asText()).isEqualTo("CREATE_UNRESOLVED");
        verify(transactions).commit(any());
    }

    @Test void partialCreationRollsBackAndCannotBeReclaimedByName() throws Exception {
        when(jdbc.update(startsWith("INSERT INTO nx_admin_role_relation"),eq(100L),eq("SUPPORT"))).thenReturn(0);
        var actors=actors();assertThatThrownBy(()->create(actors)).hasMessageContaining("fixture role");
        assertThat(actors.ownedIds()).isEmpty();verify(transactions).rollback(any());verify(transactions,never()).commit(any());
        assertThat(only("fixture-actor-outcomes").path("event").asText()).isEqualTo("CREATE_UNRESOLVED");
    }

    @Test void ambiguousCreateIsRecordedAndBlocksFurtherResourceEntry() throws Exception {
        var actors=actors();assertThatThrownBy(()->actors.createHttp("isolated_actor","offline-command",()->{throw new IOException("Ambiguous response");})).isInstanceOf(IOException.class);
        assertThat(actors.ownedIds()).isEmpty();assertThat(only("fixture-actor-outcomes").path("event").asText()).isEqualTo("CREATE_UNRESOLVED");
        clearInvocations(jdbc);assertThatThrownBy(()->create(actors)).hasMessageContaining("Unresolved");verifyNoInteractions(jdbc);
    }

    @Test void crashAfterIntentCannotSilentlyResumeCreatingMoreActors() throws Exception {
        var actors=actors();assertThatThrownBy(()->actors.createHttp("isolated_actor","offline-command",()->{throw new IOException("Interrupted");}));
        // Simulate the crash interval by preserving only the intent; these are isolated temporary unit artifacts.
        Path outcome;try(var files=Files.list(evidence("fixture-actor-outcomes"))){outcome=files.findFirst().orElseThrow();}
        Files.move(outcome,temporary.resolve("preserved-outcome.json"));clearInvocations(jdbc);
        assertThatThrownBy(()->create(actors())).hasMessageContaining("Missing or contradictory");verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked") private void cleanupSqlAndEmptyKeys() {
        when(jdbc.queryForMap("SELECT id,created_at FROM nx_admin WHERE id=?",100L)).thenReturn(Map.of("id",100L,"created_at",createdAt));
        when(jdbc.queryForMap("SELECT id,username,status,is_deleted,created_at FROM nx_admin WHERE id=?",100L)).thenReturn(Map.of("id",100L,"username","renamed-by-scenario","status",0,"is_deleted",0,"created_at",createdAt));
        when(jdbc.queryForList("SELECT * FROM nx_support_agent_profile WHERE admin_id=? ORDER BY admin_id",100L)).thenReturn(List.of(Map.of("admin_id",100L,"enabled",0,"is_deleted",0)));
        when(redis.hasKey(anyString())).thenReturn(false);SetOperations<String,String> sets=mock(SetOperations.class);when(redis.opsForSet()).thenReturn(sets);when(sets.members(anyString())).thenReturn(java.util.Set.of());
    }
    @SuppressWarnings("unchecked") private Cursor<String> cursor(String...keys) {
        Cursor<String> cursor=mock(Cursor.class);var values=java.util.Arrays.asList(keys).iterator();when(cursor.hasNext()).thenAnswer(call->values.hasNext());when(cursor.next()).thenAnswer(call->values.next());return cursor;
    }

    @Test @SuppressWarnings({"unchecked","rawtypes"}) void selfDisabledActorWithAbsentIndexStillDeletesOwnedOrphanAndScansAgain() throws Exception {
        var actors=actors();create(actors);cleanupSqlAndEmptyKeys();
        String orphan="ops:admin:session:offline-orphan";HashOperations<String,Object,Object> hashes=mock(HashOperations.class);when(redis.opsForHash()).thenReturn(hashes);when(hashes.get(orphan,"adminId")).thenReturn("100");
        when(redis.scan(any(ScanOptions.class))).thenReturn(cursor(orphan),cursor());when(redis.execute(any(RedisScript.class),eq(List.of(orphan)),eq("100"))).thenReturn(1L);
        actors.cleanup(100L);var cleanup=only("fixture-actor-cleanup");assertThat(cleanup.path("verdict").asText()).isEqualTo("pass");
        assertThat(cleanup.path("actualRedis").path("sessionNamespaceScanComplete").asBoolean()).isTrue();verify(redis,times(2)).scan(any(ScanOptions.class));
        verify(redis).execute(any(RedisScript.class),eq(List.of(orphan)),eq("100"));
    }

    @Test void scanErrorIsPersistedAsFailureEvenWhenSqlAndIndexAreClean() throws Exception {
        var actors=actors();create(actors);cleanupSqlAndEmptyKeys();when(redis.scan(any(ScanOptions.class))).thenThrow(new IllegalStateException("Redis SCAN failed"));
        assertThatThrownBy(()->actors.cleanup(100L)).isInstanceOf(AssertionError.class);
        var cleanup=only("fixture-actor-cleanup");assertThat(cleanup.path("verdict").asText()).isEqualTo("fail");assertThat(cleanup.path("errors").size()).isGreaterThanOrEqualTo(1);
        assertThat(cleanup.has("actualRedis")).isFalse();
    }

    @Test void sameOperationWithTwoActorIdsCannotCleanEitherIdentity() throws Exception {
        // Same ambiguity contract covers malformed filenames before any creator body can be parsed.
        for(String name:List.of("invalid-100.json","different-op-00100.json.partial","partial-000100.json"))
            assertThat(SupportFixtureActors.namedActorId(Path.of(name))).isEqualTo("100");
        String partialOperation="12345678-abcd-1234-abcd-123456789abc";
        assertThat(SupportFixtureActors.namedOperation(Path.of(partialOperation.toUpperCase(java.util.Locale.ROOT)+".json.partial"))).isEqualTo(partialOperation);
        assertThat(SupportFixtureActors.directoryPresent(temporary.resolve("explicitly-absent-directory"))).isFalse();
        Path nonDirectory=temporary.resolve("present-evidence-file");Files.writeString(nonDirectory,"not a directory");
        assertThatThrownBy(()->SupportFixtureActors.directoryPresent(nonDirectory)).isInstanceOf(IllegalStateException.class);
        var actors=actors();create(actors);
        var duplicate=(com.fasterxml.jackson.databind.node.ObjectNode)only("fixture-actors").deepCopy();
        String operation=duplicate.path("operationId").asText();duplicate.put("adminId",101L);
        ((com.fasterxml.jackson.databind.node.ObjectNode)duplicate.path("actor")).put("id",101L);
        ((com.fasterxml.jackson.databind.node.ObjectNode)duplicate.path("create").path("committedReadback")).put("id",101L);
        ((com.fasterxml.jackson.databind.node.ObjectNode)duplicate.path("create").path("response")).put("generatedKey",101L);
        Files.write(evidence("fixture-actors").resolve(operation+"-101.json"),json.writeValueAsBytes(duplicate));
        clearInvocations(jdbc,redis);assertThatThrownBy(()->actors.cleanup(100L)).isInstanceOf(AssertionError.class);
        verify(jdbc,never()).update("UPDATE nx_admin SET status=0 WHERE id=?",100L);
        verify(jdbc,never()).update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",100L);
        verify(redis,never()).delete("ops:admin:sessions:100");
        assertThat(only("fixture-actor-cleanup").path("verdict").asText()).isEqualTo("fail");
    }

    @Test void sameActorWithDifferentCreationOperationsCannotBeCleaned() throws Exception {
        var actors=actors();create(actors);
        var duplicate=(com.fasterxml.jackson.databind.node.ObjectNode)only("fixture-actors").deepCopy();
        String other=java.util.UUID.randomUUID().toString();duplicate.put("operationId",other);
        Files.write(evidence("fixture-actors").resolve(other+"-100.json"),json.writeValueAsBytes(duplicate));
        clearInvocations(jdbc,redis);assertThatThrownBy(()->actors.cleanup(100L)).isInstanceOf(AssertionError.class);
        verify(jdbc,never()).update("UPDATE nx_admin SET status=0 WHERE id=?",100L);
        verify(jdbc,never()).update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",100L);
        verify(redis,never()).delete("ops:admin:sessions:100");
        assertThat(only("fixture-actor-cleanup").path("verdict").asText()).isEqualTo("fail");
    }
}
