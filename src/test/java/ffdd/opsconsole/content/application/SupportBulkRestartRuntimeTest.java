package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;

import ffdd.opsconsole.NexionOpsConsoleApplication;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Harness invokes this class in a second Maven JVM against the same isolated durable database. */
@EnabledIfEnvironmentVariable(named="CS_ENHANCE_BULK_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(SupportEnhancementPreparationTest.IsolatedConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class SupportBulkRestartRuntimeTest extends SupportBulkRuntimeFixture {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {SupportEnhancementPreparationTest.isolatedBoundary(registry);}
    @Test void newJvmReadsFrozenQueuedAndPreparedRowsAndSendsExactlyOnceWithoutAuthentication() throws Exception {
        boundary();var seed=json.readTree(Files.readString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"),"bulk-restart-seed.json")));
        assertThat(seed.path("database").asText()).isEqualTo("cs_enhance_20261001");assertThat(seed.path("port").asInt()).isEqualTo(18141);assertThat(seed.path("checkedAt").asText()).isNotBlank();
        assertThat(seed.path("workflowRunId").asText()).isEqualTo(System.getenv("WORKFLOW_RUN_ID"));assertThat(seed.path("snapshotHash").asText()).isEqualTo(System.getenv("WORKFLOW_SNAPSHOT_HASH"));
        assertThat(seed.path("firstJvmPid").asLong()).isPositive().isNotEqualTo(ProcessHandle.current().pid());
        long actor=seed.path("actorId").asLong(),one=seed.path("customerIds").get(0).asLong(),two=seed.path("customerIds").get(1).asLong(),prepared=seed.path("preparedCustomerId").asLong();String batch=seed.path("batchId").asText(),preparedBatch=seed.path("preparedBatchId").asText();
        try {
            // These reads occur before any claim or processing in this new application process.
            assertThat(jdbc.queryForObject("SELECT state FROM nx_support_bulk_job WHERE id=?",String.class,batch)).isEqualTo("QUEUED");counts(batch,0,0,0,0,2);counts(preparedBatch,0,0,0,0,1);
            assertThat(jdbc.queryForObject("SELECT content_json FROM nx_support_bulk_job WHERE id=?",String.class,batch)).isEqualTo(seed.path("frozenContent").asText());
            assertThat(row(batch,one).get("client_message_id")).isEqualTo(seed.path("clientMessageIds").get(0).asText());assertThat(row(batch,two).get("client_message_id")).isEqualTo(seed.path("clientMessageIds").get(1).asText());
            assertThat(row(preparedBatch,prepared).get("client_message_id")).isEqualTo(seed.path("preparedClientMessageId").asText());assertThat(row(preparedBatch,prepared).get("request_json")).hasToString(seed.path("preparedRequestJson").asText());
            assertThat(messageCount(one)+messageCount(two)+messageCount(prepared)).isZero();assertThat(executions(one)+executions(two)+executions(prepared)).isZero();
            SecurityContextHolder.clearContext();bulk.runPending();assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            counts(batch,2,0,0,0,0);counts(preparedBatch,1,0,0,0,0);
            assertThat(row(preparedBatch,prepared).get("request_json")).hasToString(seed.path("preparedRequestJson").asText());
            for(long customer:List.of(one,two,prepared)) {assertThat(messageCount(customer)).isEqualTo(1);assertThat(executions(customer)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_maintenance_cycle WHERE customer_id=? AND status='OPEN'",Long.class,customer)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT m.sender_id FROM nx_conversation_message m JOIN nx_support_human_message h ON h.message_id=m.id WHERE h.customer_id=? AND h.actor_type='ADMIN'",Long.class,customer)).isEqualTo(actor);}
            var results=jdbc.queryForList("SELECT batch_id,customer_id,client_message_id,request_json,state,message_id,result_certainty,attempts FROM nx_support_bulk_recipient WHERE batch_id IN (?,?) ORDER BY batch_id,customer_id",batch,preparedBatch);
            bulk.runPending();send(batch,one);send(batch,two);send(preparedBatch,prepared);
            assertThat(jdbc.queryForList("SELECT batch_id,customer_id,client_message_id,request_json,state,message_id,result_certainty,attempts FROM nx_support_bulk_recipient WHERE batch_id IN (?,?) ORDER BY batch_id,customer_id",batch,preparedBatch)).isEqualTo(results);
            for(long customer:List.of(one,two,prepared)) {assertThat(messageCount(customer)).isEqualTo(1);assertThat(executions(customer)).isEqualTo(1);}
            var refreshed=http("GET",BASE+"/"+batch,token(actor),null,null);assertThat(refreshed.path("code").asInt()).isZero();assertThat(refreshed.path("data").path("counts").path("sent").asLong()).isEqualTo(2);assertThat(refreshed.path("data").path("frozenCount").asLong()).isEqualTo(2);
            proof("B09","newJvmReadsFrozenQueuedAndPreparedRowsAndSendsExactlyOnceWithoutAuthentication","Actual first/second JVM PIDs differ; before processing, same isolated DB retains QUEUED batch, exact frozen IDs/client IDs/content and another first-actual prepared DTO. New application scan with no authentication/tokens commits three original messages/executions; second scan and individual reentry preserve exact durable result rows/counts, and authenticated HTTP refresh reads original completed batch. Separate runtime suite proves rollback interruption; no SIGKILL is claimed.");writeProof("bulk-restart-runtime.json");
        } finally {SecurityContextHolder.clearContext();jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",actor);jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",actor);permissions.evict(actor);}
    }
}
