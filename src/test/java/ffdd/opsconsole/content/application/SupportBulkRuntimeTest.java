package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.content.dto.*;
import ffdd.opsconsole.finance.application.FinanceSupportReadService;
import ffdd.opsconsole.shared.exception.BizException;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named="CS_ENHANCE_BULK_ENABLED",matches="true")
@SpringBootTest(classes=NexionOpsConsoleApplication.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import({SupportEnhancementPreparationTest.IsolatedConfiguration.class,SupportBulkRuntimeFixture.EventCapture.class,SupportObjectEvidenceLedger.Configuration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SupportBulkRuntimeTest extends SupportBulkRuntimeFixture {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry registry) {SupportEnhancementPreparationTest.isolatedBoundary(registry);}
    @Autowired EventCapture events;
    @SpyBean FinanceSupportReadService finance;
    @BeforeAll void prepareFixture() {startFixture();}
    @AfterAll void restore() {events.hook=null;restoreFixture();}
    @AfterEach void clear() {events.hook=null;SecurityContextHolder.clearContext();}

    @Test @Order(1) void migrationAndHttpSelectionFreezeAuthoritativeRecipients() throws Exception {
        var oldAttachments=jdbc.queryForList("SELECT * FROM nx_support_attachment ORDER BY id");
        var oldIndexes=jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_support_attachment' AND index_name NOT IN ('uk_support_attachment_object','ix_support_attachment_object') ORDER BY index_name,seq_in_index");
        long users=jdbc.queryForObject("SELECT COUNT(*) FROM nx_user",Long.class),messages=jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message",Long.class);
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith(SupportRuntimeTarget.current().jdbcPrefix().replace("?", ""));
            var migration=new FileSystemResource("scripts/migrations/20261001_support_enhancements_bulk.sql");
            ScriptUtils.executeSqlScript(connection,migration);ScriptUtils.executeSqlScript(connection,migration);
        }
        assertThat(jdbc.queryForList("SELECT * FROM nx_support_attachment ORDER BY id")).isEqualTo(oldAttachments);
        assertThat(jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_support_attachment' AND index_name NOT IN ('uk_support_attachment_object','ix_support_attachment_object') ORDER BY index_name,seq_in_index")).isEqualTo(oldIndexes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='nx_support_attachment' AND index_name='ix_support_attachment_object' AND non_unique=1",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_user",Long.class)).isEqualTo(users);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message",Long.class)).isEqualTo(messages);
        long a=customer(first),b=customer(first),c=customer(first),excluded=customer(first),foreign=customer(second);
        assertThat(preview(first,List.of(a),List.of(),"SINGLE",null).path("count").asInt()).isEqualTo(1);
        assertThat(preview(first,List.of(a,b),List.of(),"PAGE",null).path("customers")).hasSize(2);
        assertThat(preview(first,List.of(a,b,c),List.of(b),"CROSS_PAGE",null).path("customers")).hasSize(2);
        var explicit=preview(first,List.of(a,foreign),List.of(),"EXPLICIT",null);
        assertThat(explicit.path("customers")).hasSize(1);assertThat(explicit.path("excluded").toString()).contains("NOT_CURRENT_CUSTOMER");
        var filters=filter(null,null,null,null,null,null,null,null,null,null,null,null,null,run);
        var frozen=preview(first,List.of(),List.of(excluded),"ALL_FILTERED",filters);
        assertThat(ids(frozen.path("customers"))).containsExactly(a,b,c);assertThat(frozen.path("excluded").toString()).contains("EXPLICITLY_EXCLUDED");
        assertThat(frozen.path("evaluatedAt").asText()).endsWith("Z");assertThat(frozen.path("expiresAt").asText()).endsWith("Z");
        long arrival=customer(first);String batch=frozen.path("selectionId").asText(),command=key();
        var create=new SupportBulkRequest.Create(batch,"SERVICE","TEXT","Frozen template text",null,null,null,"Frozen HTTP selection proof");
        var created=http("POST",BASE,token(first),create,command);assertThat(created.path("code").asInt()).isZero();
        assertThat(created.path("data").path("frozenCount").asLong()).isEqualTo(3L);assertThat(created.path("data").path("actorId").asLong()).isEqualTo(first);
        assertThat(http("POST",BASE,token(first),create,command).path("data").path("batchId").asText()).isEqualTo(batch);
        assertThat(http("POST",BASE,token(first),new SupportBulkRequest.Create(batch,"SERVICE","TEXT","Changed body",null,null,null,create.reason()),command).path("code").asInt()).isEqualTo(409);
        var pages=new LinkedHashSet<Long>();
        for(int page=1;page<=3;page++) {var response=http("GET",BASE+"/"+batch+"/recipients?pageNum="+page+"&pageSize=1",token(first),null,null);assertThat(response.path("code").asInt()).isZero();assertThat(response.path("data").path("total").asLong()).isEqualTo(3);for(var row:response.path("data").path("records"))assertThat(pages.add(row.path("customerId").asLong())).isTrue();}
        assertThat(pages).containsExactly(a,b,c);assertThat(pages).doesNotContain(arrival,excluded,foreign);
        assertThat(http("GET",BASE+"?pageSize=1&pageNum=1",token(first),null,null).path("data").path("records")).hasSize(1);
        var recovery=http("GET","/api/admin/content/support-workbench/commands/"+command,token(first),null,null);assertThat(recovery.path("data").path("status").asText()).isEqualTo("SUCCEEDED");assertThat(recovery.toString()).contains(batch);
        jdbc.update("UPDATE nx_admin_idempotency_record SET status='UNKNOWN',response_json=NULL WHERE scope=? AND idempotency_key=?","M3_SUPPORT_BULK_CREATE:"+first,command);
        assertThat(http("GET","/api/admin/content/support-workbench/commands/"+command,token(first),null,null).path("data").path("status").asText()).isEqualTo("SUCCEEDED");
        jdbc.update("DELETE FROM nx_admin_idempotency_record WHERE scope=? AND idempotency_key=?","M3_SUPPORT_BULK_CREATE:"+first,command);
        assertThat(http("GET","/api/admin/content/support-workbench/commands/"+command,token(first),null,null).path("data").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(http("POST",BASE+"/preview",token(boss),new SupportBulkRequest.Preview(null,List.of(a),List.of(),"SINGLE"),null).path("code").asInt()).isEqualTo(403);
        var beforeDraft=messageCount(a);preview(first,List.of(a),List.of(),"PAGE",null);assertThat(messageCount(a)).isEqualTo(beforeDraft);
        String expiredSelection=preview(first,List.of(a),List.of(),"SINGLE",null).path("selectionId").asText();
        jdbc.update("UPDATE nx_support_bulk_job SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",expiredSelection);
        var expired=http("POST",BASE,token(first),new SupportBulkRequest.Create(expiredSelection,"SERVICE","TEXT","Expired preview must not send",null,null,null,"Reject actual UTC preview expiry"),key());
        assertThat(expired.path("code").asInt()).as("Near-now preview expiry: %s",expired.path("message")).isEqualTo(409);assertThat(messageCount(a)).isEqualTo(beforeDraft);
        cancel(first,batch);counts(batch,0,0,0,3,0);
        proof("B01","migrationAndHttpSelectionFreezeAuthoritativeRecipients","Backend preview/create/detail/recipients/command recovery and ordinary single-customer protocol exercised through real authenticated HTTP; two UI entrances are P3 scope.");
        proof("B02","migrationAndHttpSelectionFreezeAuthoritativeRecipients","SINGLE/PAGE/CROSS_PAGE/EXPLICIT/ALL_FILTERED persisted exact IDs; foreign/excluded customers excluded; later matching customer absent; three HTTP pages have no omissions or duplicates.");
        proof("B04","migrationAndHttpSelectionFreezeAuthoritativeRecipients","Backend confirmation supplies frozen IDs/count/exclusion/actor/intent/content and UTC evaluation/expiry; preview writes no human messages. P3 input preservation and UI exits remain outside this backend suite.");
        proof("X01","migrationAndHttpSelectionFreezeAuthoritativeRecipients","Same isolated JDBC connection applied additive migration twice; historical attachment rows, unrelated indexes, user/message counts unchanged; object lookup index is nonunique.");writeProof("bulk-runtime.json");
    }

    @Test @Order(2) void filtersKeepUtcMoneyPrecisionAndUnknownSeparateFromZero() throws Exception {
        long known=customer(first),dormant=customer(first),unknown=customer(first);effective(known);effective(dormant);
        jdbc.update("UPDATE nx_support_activity_event SET occurred_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 DAY) WHERE customer_id=?",dormant);
        jdbc.update("UPDATE nx_support_activity_state SET last_effective_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 10 DAY) WHERE customer_id=?",dormant);
        var coverage=jdbc.queryForObject("SELECT coverage_start_at FROM nx_support_activity_coverage WHERE id=1",java.sql.Timestamp.class);
        try {
            jdbc.update("UPDATE nx_support_activity_coverage SET coverage_start_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 DAY) WHERE id=1");
            for(long customer:List.of(known,dormant,unknown))jdbc.update("UPDATE nx_user SET v_rank='V3',created_at='2026-09-01 08:00:00' WHERE id=?",customer);
            jdbc.update("INSERT INTO nx_customer_tag(user_id,tag,last_operator,is_deleted,created_at,updated_at) VALUES(?,'bulk-authoritative',?,0,NOW(),NOW())",known,run);
            for(String table:List.of("nx_wallet_ledger","nx_deposit_order","nx_withdrawal_order"))
                assertThat(jdbc.queryForObject("SELECT numeric_scale FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='amount'",Integer.class,table)).as("Actual %s amount precision",table).isEqualTo(6);
            deposit(known,"USDT","0.1");deposit(known,"USDT","0.2");deposit(known,"NEX","99.000001");withdrawal(known,"0.125001");
            for(String table:List.of("nx_wallet_ledger","nx_deposit_order")) {
                assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM "+table+" WHERE user_id=? AND asset='USDT'",java.math.BigDecimal.class,known)).as("Persisted %s USDT",table).isEqualByComparingTo("0.3");
                assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM "+table+" WHERE user_id=? AND asset='NEX'",java.math.BigDecimal.class,known)).as("Persisted %s NEX",table).isEqualByComparingTo("99.000001");
            }
            assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM nx_withdrawal_order WHERE user_id=? AND asset='USDT'",java.math.BigDecimal.class,known)).as("Persisted withdrawal principal").isEqualByComparingTo("0.125001");
            // A successful source lacking a committed matching ledger is genuinely UNKNOWN.
            jdbc.update("INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,created_at) VALUES(?,?,'TRC20',?,'USDT',1,'SUCCESS',NOW())",unknown,key(),key());
            String activityFrom=Instant.now().minusSeconds(120).toString(),activityTo=Instant.now().plusSeconds(120).toString();
            var active=preview(first,List.of(known,dormant,unknown),List.of(),"EXPLICIT",filter("ACTIVE","DUE","V3",List.of("bulk-authoritative"),"2026-09-01T00:00:00Z","2026-09-02T00:00:00Z",activityFrom,activityTo,"0.3","0.3","0.125001","0.125001",false,null));
            var query=SupportWorkbenchService.query(first,known,bindingMapper.rules(),activity.checkpoint());query.put("ids",List.of(known));
            var candidate=bulkMapper.candidates(query).get(0);
            var mapping=new LinkedHashMap<String,Object>();
            for(String field:List.of("registeredAt","lastEffectiveAt")) {
                Object value=candidate.get(field);mapping.put(field+"Class",value==null?"null":value.getClass().getName());mapping.put(field+"Value",String.valueOf(value));
                if(value instanceof java.sql.Timestamp timestamp)mapping.put(field+"LocalDateTime",timestamp.toLocalDateTime().toString());
            }
            var source=jdbc.queryForObject("SELECT DATE_FORMAT(CONVERT_TZ(u.created_at,'+08:00','+00:00'),'%Y-%m-%dT%H:%i:%s.%f') registeredUtc,MAX(e.occurred_at) activityAt,DATE_FORMAT(MAX(e.occurred_at),'%Y-%m-%dT%H:%i:%s.%f') activityUtc FROM nx_user u LEFT JOIN nx_support_activity_event e ON e.customer_id=u.id WHERE u.id=? GROUP BY u.id,u.created_at",(rs,n)->Map.of("registeredUtc",rs.getString("registeredUtc"),"activityUtc",rs.getString("activityUtc"),"activityLocalDateTime",rs.getObject("activityAt",LocalDateTime.class).toString()),known);
            assertThat(ids(active.path("customers"))).as("Combined filter customer=%s excluded=%s account=%s due=%s level=%s tag=%s bounds=[%s,%s) mapping=%s source=%s finance=%s",known,active.path("excluded"),candidate.get("accountState"),candidate.get("due"),candidate.get("level"),bulkMapper.hasTag(known,"bulk-authoritative"),activityFrom,activityTo,mapping,source,finance.totals(known)).containsExactly(known);
            assertThat(ids(preview(first,List.of(known,dormant,unknown),List.of(),"EXPLICIT",filter("DORMANT",null,null,null,null,null,null,null,null,null,null,null,false,null)).path("customers"))).containsExactly(dormant);
            assertThat(ids(preview(first,List.of(unknown),List.of(),"EXPLICIT",filter("UNKNOWN",null,null,null,null,null,null,null,null,null,null,null,false,null)).path("customers"))).containsExactly(unknown);
            var amount=filter(null,null,null,null,null,null,null,null,"0","0",null,null,false,null);
            assertThat(preview(first,List.of(unknown),List.of(),"EXPLICIT",amount).path("excluded").toString()).contains("FINANCE_UNKNOWN");
            assertThat(preview(first,List.of(unknown),List.of(),"EXPLICIT",filter(null,null,null,null,null,null,null,null,"0","0",null,null,true,null)).path("count").asInt()).isEqualTo(1);
            var money=json.valueToTree(finance.totals(known));
            var usdt=java.util.stream.StreamSupport.stream(money.path("byCurrency").spliterator(),false).filter(row->"USDT".equals(row.path("currency").asText())).findFirst().orElseThrow();
            var nex=java.util.stream.StreamSupport.stream(money.path("byCurrency").spliterator(),false).filter(row->"NEX".equals(row.path("currency").asText())).findFirst().orElseThrow();
            assertThat(new java.math.BigDecimal(usdt.path("creditedDepositTotal").asText())).isEqualByComparingTo("0.3");assertThat(new java.math.BigDecimal(usdt.path("successfulWithdrawalPrincipalTotal").asText())).isEqualByComparingTo("0.125001");assertThat(new java.math.BigDecimal(nex.path("creditedDepositTotal").asText())).isEqualByComparingTo("99.000001");
            // Fault injection is limited to the authoritative source reader; bulk/HTTP/storage remain real.
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("Isolated financial source failure")).when(finance).totals(known);
            try {var error=preview(first,List.of(known),List.of(),"EXPLICIT",amount);assertThat(error.path("count").asInt()).isZero();assertThat(error.path("excluded").toString()).contains("FINANCE_ERROR");}
            finally {reset(finance);}
            Integer days=bindingMapper.rules().maintenanceDays();
            try {
                SharedMutationJournal.sql(jdbc,run,"SupportBulkRuntimeTest",boss,"SupportBulkRuntimeTest#rules-sql-1","UPDATE nx_support_rules SET maintenance_days=NULL,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE id=1",boss);
                var missing=preview(first,List.of(known),List.of(),"EXPLICIT",filter(null,"DUE",null,null,null,null,null,null,null,null,null,null,false,null));assertThat(missing.path("count").asInt()).isZero();assertThat(missing.path("excluded").toString()).contains("MAINTENANCE_UNKNOWN");
                assertThat(preview(first,List.of(known),List.of(),"EXPLICIT",filter(null,"DUE",null,null,null,null,null,null,null,null,null,null,true,null)).path("count").asInt()).isEqualTo(1);
            } finally {SharedMutationJournal.cleanupSql(jdbc,run,"SupportBulkRuntimeTest",boss,"SupportBulkRuntimeTest#rules-sql-2","UPDATE nx_support_rules SET maintenance_days=?,version=version+1,updated_by=?,updated_at=UTC_TIMESTAMP(6) WHERE id=1",days,boss);}
            assertThat(preview(first,List.of(known),List.of(),"EXPLICIT",filter(null,"DUE",null,null,null,null,null,null,null,null,null,null,false,null)).path("count").asInt()).isEqualTo(1);
        } finally {jdbc.update("UPDATE nx_support_activity_coverage SET coverage_start_at=? WHERE id=1",coverage);}
        proof("B03","filtersKeepUtcMoneyPrecisionAndUnknownSeparateFromZero","Real activity projection distinguishes ACTIVE/DORMANT/UNKNOWN; exact UTC registration/activity bounds, DUE, V3, stored string tag and currency-specific decimal sums match. Actual deposit/ledger/withdrawal amount columns retain six decimal places; raw persisted USDT 0.1+0.2=0.3, withdrawal 0.125001 and NEX 99.000001 exactly match the authoritative source and identical inclusive amount bounds. Unlinked successful deposit stays UNKNOWN and only explicit includeUnknown admits it; injected authoritative-source failure yields FINANCE_ERROR rather than zero. Real unset maintenanceDays makes DUE unknown, excluded by default and included only explicitly; restored configuration returns known DUE.");writeProof("bulk-runtime.json");
    }

    @Test @Order(3) void serviceMaintenanceAndStoppedHelpPreservePendingCursor() throws Exception {
        long service=customer(first),maintained=customer(first),stopped=customer(first);
        String client=userToken(service);
        var opening=http("POST","/api/app/support/conversations",client,Map.of("conversationType","advisor","openingText","Unanswered customer help"),key());assertThat(opening.path("code").asInt()).isZero();String no=opening.path("data").path("conversation").path("conversationNo").asText();
        long pending=bindingMapper.pendingReplies(no);assertThat(pending).isPositive();
        var cursors=jdbc.queryForList("SELECT * FROM nx_support_reply_cursor WHERE conversation_no=?",no);
        String ordinary=batch(first,List.of(service),"SERVICE","TEXT","Ordinary shared template text",null,null,null);send(ordinary,service);
        counts(ordinary,1,0,0,0,0);assertThat(executions(service)).isZero();assertThat(bindingMapper.pendingReplies(no)).isEqualTo(pending);assertThat(jdbc.queryForList("SELECT * FROM nx_support_reply_cursor WHERE conversation_no=?",no)).isEqualTo(cursors);
        var dto=json.readTree(row(ordinary,service).get("request_json").toString());assertThat(dto.path("replyTargets")).isEmpty();assertThat(dto.path("replyThroughMessageId").isNull() || dto.path("replyThroughMessageId").isMissingNode()).isTrue();
        String batch=batch(first,List.of(maintained),"MAINTENANCE","TEXT","Actual maintenance text",null,null,null);send(batch,maintained);send(batch,maintained);
        assertThat(messageCount(maintained)).isEqualTo(1);assertThat(executions(maintained)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_maintenance_cycle WHERE customer_id=? AND status='OPEN'",Long.class,maintained)).isEqualTo(1);
        effective(maintained);effective(maintained);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_maintenance_cycle WHERE customer_id=? AND status='SUCCEEDED'",Long.class,maintained)).isEqualTo(1);
        as(first);var assignment=bindingMapper.current(stopped);assertThat(maintenance.change(stopped,false,"Stop proactive maintenance",assignment.id(),1L,key()).getCode()).isZero();
        var denied=preview(first,List.of(stopped),List.of(),"EXPLICIT",null);assertThat(denied.path("count").asInt()).isZero();assertThat(denied.path("excluded").toString()).contains("MAINTENANCE_STOPPED");
        String helpClient=userToken(stopped);var help=http("POST","/api/app/support/conversations",helpClient,Map.of("conversationType","advisor","openingText","Stopped customer asks for help"),key());assertThat(help.path("code").asInt()).isZero();String helpNo=help.path("data").path("conversation").path("conversationNo").asText();
        var helpReply=reply(stopped,helpNo,"Ordinary help remains permitted",key());
        assertThat(http("POST","/api/admin/content/conversations/"+helpNo+"/replies",token(first),helpReply,key()).path("code").asInt()).isZero();assertThat(executions(stopped)).isZero();
        long laterStopped=customer(first);String s=batch(first,List.of(laterStopped),"SERVICE","TEXT","Stop after snapshot",null,null,null);
        as(first);assertThat(maintenance.change(laterStopped,false,"Stop before worker send",bindingMapper.current(laterStopped).id(),1L,key()).getCode()).isZero();send(s,laterStopped);counts(s,0,0,1,0,0);assertThat(messageCount(laterStopped)).isZero();
        long laterMaintenanceStopped=customer(first);String m=batch(first,List.of(laterMaintenanceStopped),"MAINTENANCE","TEXT","Stop after maintenance snapshot",null,null,null);
        as(first);assertThat(maintenance.change(laterMaintenanceStopped,false,"Stop before maintenance send",bindingMapper.current(laterMaintenanceStopped).id(),1L,key()).getCode()).isZero();send(m,laterMaintenanceStopped);counts(m,0,0,1,0,0);assertThat(executions(laterMaintenanceStopped)).isZero();
        var current=jdbc.queryForMap("SELECT status,version FROM nx_conversation WHERE conversation_no=?",no);
        assertThat(http("POST","/api/app/support/conversations/"+no+"/read",client,Map.of("lastSeenMessageId",row(ordinary,service).get("message_id"),"expectedStatus",current.get("status"),"expectedVersion",current.get("version")),null).path("code").asInt()).isZero();assertThat(executions(service)).isZero();
        proof("B05","serviceMaintenanceAndStoppedHelpPreservePendingCursor","Real SERVICE commits zero executions; duplicated MAINTENANCE runner commits one message/execution and a later actual validated interactive activity closes exactly one successful cycle.");
        proof("B06","serviceMaintenanceAndStoppedHelpPreservePendingCursor","Stopped customer excluded from selection; both SERVICE and MAINTENANCE frozen sends skip after stop. Actual stopped customer help and ordinary HTTP reply still succeed without maintenance execution.");
        proof("B13","serviceMaintenanceAndStoppedHelpPreservePendingCursor","Existing user pending message and reply cursor survive bulk SERVICE; frozen DTO replyTargets=[] and no through ID; ordinary read creates no maintenance execution.");writeProof("bulk-runtime.json");
    }

    @Test @Order(4) void workerRechecksTransferSeatAndOriginalGrantWithoutAmbientIdentity() throws Exception {
        long moved=customer(first),disabled=customer(first),inactive=customer(first),revoked=customer(first),none=customer(first),stale=customer(first);
        String transferBatch=batch(first,List.of(moved),"MAINTENANCE","TEXT","Transfer before send",null,null,null);transfer(second,moved);send(transferBatch,moved);counts(transferBatch,0,0,1,0,0);assertThat(messageCount(moved)).isZero();
        String disableBatch=batch(first,List.of(disabled),"SERVICE","TEXT","Disable before send",null,null,null);
        try {jdbc.update("UPDATE nx_support_agent_profile SET enabled=0 WHERE admin_id=?",first);send(disableBatch,disabled);counts(disableBatch,0,0,1,0,0);assertThat(messageCount(disabled)).isZero();}
        finally {jdbc.update("UPDATE nx_support_agent_profile SET enabled=1 WHERE admin_id=?",first);}
        String inactiveBatch=batch(first,List.of(inactive),"SERVICE","TEXT","Account disabled before send",null,null,null);
        try {jdbc.update("UPDATE nx_admin SET status=0 WHERE id=?",first);send(inactiveBatch,inactive);counts(inactiveBatch,0,0,1,0,0);assertThat(messageCount(inactive)).isZero();}
        finally {jdbc.update("UPDATE nx_admin SET status=1 WHERE id=?",first);permissions.evict(first);}
        String revokeBatch=batch(first,List.of(revoked),"SERVICE","TEXT","Grant revoked before send",null,null,null);
        var grants=jdbc.queryForList("SELECT rp.id FROM nx_admin_role_permission rp JOIN nx_admin_role r ON r.id=rp.role_id JOIN nx_admin_permission p ON p.id=rp.permission_id WHERE r.role_code='SUPPORT' AND p.permission_code='service_m3_write' AND rp.is_deleted=0",Long.class);assertThat(grants).isNotEmpty();
        try {grants.forEach(id->SharedMutationJournal.sql(jdbc,run,"SupportBulkRuntimeTest",boss,"SupportBulkRuntimeTest#grant-disable-1","UPDATE nx_admin_role_permission SET is_deleted=1 WHERE id=?",id));send(revokeBatch,revoked);counts(revokeBatch,0,0,1,0,0);assertThat(messageCount(revoked)).isZero();}
        finally {grants.forEach(id->SharedMutationJournal.restorePermission(jdbc,run,"SupportBulkRuntimeTest",boss,"SupportBulkRuntimeTest#grant-restore-2",id));permissions.evict(first);permissions.evict(second);}
        String noAuth=batch(first,List.of(none),"MAINTENANCE","TEXT","Worker has no identity",null,null,null);send(noAuth,none);assertAuthor(noAuth,none,first);assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        String staleAuth=batch(first,List.of(stale),"MAINTENANCE","TEXT","Worker holds other advisor identity",null,null,null);as(second);bulk.processRecipient(staleAuth,stale);assertAuthor(staleAuth,stale,first);assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(String.valueOf(second));
        assertThat(executions(none)).isEqualTo(1);assertThat(executions(stale)).isEqualTo(1);
        proof("B07","workerRechecksTransferSeatAndOriginalGrantWithoutAmbientIdentity","Real formal transfer, disabled dedicated profile, and revoked original service_m3_write DB grant each skip before commit. No-auth and stale-other-auth workers persist actual sender, metadata, maintenance owner and trusted audit as durable task actor.");writeProof("bulk-runtime.json");
    }

    @Test @Order(5) void failuresRetryFrozenPayloadAndUnknownReconcilesBeforeEligibility() throws Exception {
        long failed=customer(first);String sku=sku("retry",false),retryBatch=batch(first,List.of(failed),"MAINTENANCE","SKU","Retry original caption",sku,null,null);
        send(retryBatch,failed);counts(retryBatch,0,1,0,0,0);var initial=row(retryBatch,failed);
        assertThat(initial.get("failure_code")).isEqualTo("SUPPORT_SKU_UNAVAILABLE");assertThat(messageCount(failed)).isZero();assertThat(executions(failed)).isZero();
        String request=initial.get("request_json").toString(),client=initial.get("client_message_id").toString();
        jdbc.update("UPDATE nx_product SET status='ON_SALE',store_status='on' WHERE product_no=?",sku);
        as(first);long version=jdbc.queryForObject("SELECT version FROM nx_support_bulk_job WHERE id=?",Long.class,retryBatch);String retryKey=key();
        assertThat(bulk.retry(retryBatch,retryKey,new SupportBulkRequest.Mutation(version,"Retry original failed recipient")).getCode()).isZero();send(retryBatch,failed);send(retryBatch,failed);
        counts(retryBatch,1,0,0,0,0);assertThat(row(retryBatch,failed).get("request_json")).hasToString(request);assertThat(row(retryBatch,failed).get("client_message_id")).isEqualTo(client);
        assertThat(messageCount(failed)).isEqualTo(1);assertThat(executions(failed)).isEqualTo(1);assertThat(http("GET","/api/admin/content/support-workbench/commands/"+retryKey,token(first),null,null).path("data").path("status").asText()).isEqualTo("SUCCEEDED");

        long rollback=customer(first);String interrupted=batch(first,List.of(rollback),"MAINTENANCE","TEXT","Rollback interrupted send",null,null,null);String constraint="bulk_fault_"+run.substring(5);boolean installed=false;
        try {
            jdbc.execute("ALTER TABLE nx_support_human_message ADD CONSTRAINT "+constraint+" CHECK(customer_id<>"+rollback+")");installed=true;
            events.events.clear();send(interrupted,rollback);var uncertain=row(interrupted,rollback);
            assertThat(uncertain.get("state")).isEqualTo("PENDING");assertThat(uncertain.get("result_certainty")).isEqualTo("UNKNOWN");assertThat(uncertain.get("request_json")).isNotNull();assertThat(messageCount(rollback)).isZero();assertThat(executions(rollback)).isZero();assertThat(events.events).isEmpty();
            String original=uncertain.get("request_json").toString();jdbc.execute("ALTER TABLE nx_support_human_message DROP CHECK "+constraint);installed=false;
            send(interrupted,rollback);assertThat(row(interrupted,rollback).get("request_json")).hasToString(original);counts(interrupted,1,0,0,0,0);assertThat(executions(rollback)).isEqualTo(1);assertThat(events.events).hasSize(1);
        } finally {if(installed)jdbc.execute("ALTER TABLE nx_support_human_message DROP CHECK "+constraint);}

        long committed=customer(first);String body="Transferred private committed text "+run;String batch=batch(first,List.of(committed),"MAINTENANCE","TEXT",body,null,null,null);
        var lost=new java.util.concurrent.atomic.AtomicBoolean();events.events.clear();events.hook=event->{if(body.equals(event.getBody()) && lost.compareAndSet(false,true))throw new IllegalStateException("Isolated after-commit response loss");};
        send(batch,committed);events.hook=null;assertThat(lost).isTrue();assertThat(messageCount(committed)).isEqualTo(1);assertThat(executions(committed)).isEqualTo(1);assertThat(row(batch,committed).get("state")).isEqualTo("SENT");
        long message=((Number)row(batch,committed).get("message_id")).longValue();String original=row(batch,committed).get("request_json").toString();
        // Persist an external UNKNOWN receipt after the real send committed, then revoke customer scope.
        jdbc.update("UPDATE nx_support_bulk_recipient SET state='PENDING',result_certainty='UNKNOWN',message_id=NULL WHERE batch_id=? AND customer_id=?",batch,committed);
        transfer(second,committed);send(batch,committed);counts(batch,1,0,0,0,0);assertThat(row(batch,committed).get("message_id")).isEqualTo(message);assertThat(messageCount(committed)).isEqualTo(1);assertThat(executions(committed)).isEqualTo(1);
        var own=http("GET",BASE+"/"+batch,token(first),null,null);assertThat(own.path("code").asInt()).isZero();assertThat(own.path("data").path("contentRestricted").asBoolean()).isTrue();assertThat(own.toString()).doesNotContain(body);
        assertThat(http("GET",BASE+"/"+batch+"/recipients",token(first),null,null).path("data").path("records")).isEmpty();
        String creation=jdbc.queryForObject("SELECT command_key FROM nx_support_bulk_job WHERE id=?",String.class,batch);assertThat(http("GET","/api/admin/content/support-workbench/commands/"+creation,token(first),null,null).toString()).doesNotContain(body);
        assertThat(http("GET",BASE+"/"+batch+"/recipients",token(boss),null,null).path("data").path("records")).hasSize(1);
        // An existing fact without its frozen DTO must never be falsely cancelled or sent again.
        jdbc.update("UPDATE nx_support_bulk_recipient SET request_json=NULL,state='PENDING',result_certainty='UNKNOWN',message_id=NULL WHERE batch_id=? AND customer_id=?",batch,committed);
        cancel(first,batch);assertThat(row(batch,committed).get("state")).isEqualTo("PENDING");assertThat(row(batch,committed).get("result_certainty")).isEqualTo("UNKNOWN");assertThat(messageCount(committed)).isEqualTo(1);
        jdbc.update("UPDATE nx_support_bulk_recipient SET request_json=? WHERE batch_id=? AND customer_id=?",original,batch,committed);send(batch,committed);counts(batch,1,0,0,0,0);

        long noDto=customer(first);String noDtoBatch=batch(first,List.of(noDto),"SERVICE","TEXT","Recover preparation failure",null,null,null);
        String preparationConstraint="bulk_prepare_"+run.substring(5);boolean preparingFault=false;
        try {
            jdbc.execute("ALTER TABLE nx_support_bulk_recipient ADD CONSTRAINT "+preparationConstraint+" CHECK(customer_id<>"+noDto+" OR request_json IS NULL)");preparingFault=true;
            send(noDtoBatch,noDto);assertThat(row(noDtoBatch,noDto).get("result_certainty")).isEqualTo("UNKNOWN");assertThat(row(noDtoBatch,noDto).get("request_json")).isNull();assertThat(messageCount(noDto)).isZero();
            jdbc.execute("ALTER TABLE nx_support_bulk_recipient DROP CHECK "+preparationConstraint);preparingFault=false;
            send(noDtoBatch,noDto);counts(noDtoBatch,1,0,0,0,0);assertThat(messageCount(noDto)).isEqualTo(1);
        } finally {if(preparingFault)jdbc.execute("ALTER TABLE nx_support_bulk_recipient DROP CHECK "+preparationConstraint);}
        proof("B08","failuresRetryFrozenPayloadAndUnknownReconcilesBeforeEligibility","Off-sale SKU retry retains exact original client/DTO and produces one message/execution; real CHECK failure rolls back message/execution/event while durable prepared DTO survives. Actual after-commit response-loss fault plus persisted UNKNOWN and formal transfer resolves original real message to SENT before new eligibility, keeps fixed counts and hides current-revoked public detail. Missing DTO with an existing fact remains UNKNOWN; proven fact absence resumes preparation.");
        proof("X01","failuresRetryFrozenPayloadAndUnknownReconcilesBeforeEligibility","Actual bulk JDBC rollback publishes no ConversationMessageEvent; successful retry emits one after commit. Throwing from an actual after-commit callback leaves exactly one committed message/execution and recovery never duplicates it.");writeProof("bulk-runtime.json");
    }

    @Test @Order(6) void frozenPreparationCompetingRunnersAndCancellationLinearize() throws Exception {
        long prepared=customer(first);String client=userToken(prepared);var opened=http("POST","/api/app/support/conversations",client,Map.of("conversationType","advisor","openingText","Version freeze help"),key());assertThat(opened.path("code").asInt()).isZero();String no=opened.path("data").path("conversation").path("conversationNo").asText();
        String batch=batch(first,List.of(prepared),"SERVICE","TEXT","Keep first prepared version",null,null,null);var executor=Executors.newFixedThreadPool(3);
        try {
            var go=new CountDownLatch(1);var one=executor.submit(()->{SecurityContextHolder.clearContext();go.await();bulk.prepareRecipient(batch,prepared);return true;});var two=executor.submit(()->{SecurityContextHolder.clearContext();go.await();bulk.prepareRecipient(batch,prepared);return true;});go.countDown();assertThat(one.get(20,TimeUnit.SECONDS)).isTrue();String frozen=row(batch,prepared).get("request_json").toString();assertThat(two.get(20,TimeUnit.SECONDS)).isTrue();assertThat(row(batch,prepared).get("request_json")).hasToString(frozen);
            jdbc.update("UPDATE nx_conversation SET version=version+1 WHERE conversation_no=?",no);send(batch,prepared);counts(batch,0,1,0,0,0);assertThat(row(batch,prepared).get("request_json")).hasToString(frozen);assertThat(row(batch,prepared).get("retryable").toString()).isIn("false","0");assertThat(messageCount(prepared)).isZero();

            long cancelFirst=customer(first);String cancelBatch=batch(first,List.of(cancelFirst),"SERVICE","TEXT","Cancelled before send",null,null,null);SecurityContextHolder.clearContext();bulk.prepareRecipient(cancelBatch,cancelFirst);String original=row(cancelBatch,cancelFirst).get("request_json").toString();
            var customerLocked=new CountDownLatch(1);var releaseCustomer=new CountDownLatch(1);
            var holder=executor.submit(()->new TransactionTemplate(transactions).execute(status->{bindingMapper.lockCustomer(cancelFirst);customerLocked.countDown();await(releaseCustomer);return true;}));
            assertThat(customerLocked.await(5,TimeUnit.SECONDS)).isTrue();var delivery=executor.submit(()->{send(cancelBatch,cancelFirst);return true;});assertThatThrownBy(()->delivery.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            cancel(first,cancelBatch);counts(cancelBatch,0,0,0,1,0);releaseCustomer.countDown();assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();assertThat(delivery.get(15,TimeUnit.SECONDS)).isTrue();assertThat(messageCount(cancelFirst)).isZero();assertThat(row(cancelBatch,cancelFirst).get("request_json")).hasToString(original);

            long sendFirst=customer(first),remaining=customer(first);String sendBatch=batch(first,List.of(sendFirst,remaining),"MAINTENANCE","TEXT","Commit wins cancellation",null,null,null);SecurityContextHolder.clearContext();bulk.prepareRecipient(sendBatch,sendFirst);
            var fenceLocked=new CountDownLatch(1);var releaseFence=new CountDownLatch(1);
            var fence=executor.submit(()->new TransactionTemplate(transactions).execute(status->{jdbc.queryForObject("SELECT id FROM nx_support_activity_coverage WHERE id=1 FOR UPDATE",Long.class);fenceLocked.countDown();await(releaseFence);return true;}));
            assertThat(fenceLocked.await(5,TimeUnit.SECONDS)).isTrue();var sending=executor.submit(()->{send(sendBatch,sendFirst);return true;});awaitJobHeld(sendBatch);
            long staleVersion=jdbc.queryForObject("SELECT version FROM nx_support_bulk_job WHERE id=?",Long.class,sendBatch);
            var cancellation=executor.submit(()->{as(first);try{return bulk.cancel(sendBatch,key(),new SupportBulkRequest.Mutation(staleVersion,"Concurrent cancellation after send lock")).getCode();}catch(BizException ex){return ex.getCode();}finally{SecurityContextHolder.clearContext();}});
            assertThatThrownBy(()->cancellation.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);releaseFence.countDown();assertThat(fence.get(10,TimeUnit.SECONDS)).isTrue();assertThat(sending.get(15,TimeUnit.SECONDS)).isTrue();assertThat(cancellation.get(15,TimeUnit.SECONDS)).isEqualTo(409);
            cancel(first,sendBatch);counts(sendBatch,1,0,0,1,0);assertThat(messageCount(sendFirst)).isEqualTo(1);assertThat(executions(sendFirst)).isEqualTo(1);assertThat(messageCount(remaining)).isZero();assertThat(executions(remaining)).isZero();
        } finally {executor.shutdownNow();}
        proof("B10","frozenPreparationCompetingRunnersAndCancellationLinearize","Two real runners preserve one first prepared DTO. Intervening header version change fails without silent DTO refresh. Customer-row lock proves cancel needs no customer lock and wins before send; activity-fence lock plus NOWAIT job-lock probe proves send owns job and cancel blocks until commit, returns stale-version conflict, then refreshed cancel retains one SENT and cancels one remaining with exact fixed counts.");writeProof("bulk-runtime.json");
    }

    @Test @Order(7) void oneImageOwnsIsolatedPrivateReferencesAndRetainsCommittedBytes() throws Exception {
        long c=objectCustomer(first),d=objectCustomer(first),unsent=objectCustomer(first);byte[] image=png(0xCC5500);String uploadId=key(),uploadKey=key();
        var uploaded=upload(first,image,uploadId,uploadKey);assertThat(uploaded.path("code").asInt()).isZero();String asset=uploaded.path("data").path("assetId").asText();assertThat(asset).isNotBlank();
        assertThat(upload(first,image,uploadId,uploadKey).path("data").path("assetId").asText()).isEqualTo(asset);
        assertThat(upload(first,image,uploadId,key()).path("code").asInt()).isEqualTo(409);
        assertThat(upload(first,png(0x112233),uploadId,uploadKey).path("code").asInt()).isEqualTo(409);
        String unknownUpload=key(),unknownKey=key();var callbacks=new java.util.concurrent.atomic.AtomicReference<List<org.springframework.transaction.support.TransactionSynchronization>>();
        var committedIntent=objectRequest(SupportObjectEvidenceLedger.Kind.BULK_ASSET,first,null,null,unknownUpload,unknownKey,null,storageProperties.getBucket(),false);
        var committedAsset=objects().direct(committedIntent,()->new TransactionTemplate(transactions).execute(status->{
            as(first);var value=attachments.uploadBulk(first,unknownKey,unknownUpload,new org.springframework.mock.web.MockMultipartFile("file","actual.png","image/png",image));
            callbacks.set(org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().stream().filter(sync->sync.getClass().getEnclosingClass()==SupportAttachmentService.class).toList());return value;
        }));
        assertThat(callbacks.get()).hasSize(1);String committedAssetId=committedAsset.get("assetId").toString();String committedObject=json.readTree(jdbc.queryForObject("SELECT asset_json FROM nx_support_bulk_job WHERE id=?",String.class,committedAssetId)).path("objectKey").asText();assertThat(storage.exists(committedObject)).isTrue();
        callbacks.get().forEach(sync->sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN));
        objects().callbackObservation(committedIntent,org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN,
            "Actual bulk upload callback explicitly replayed after the real transaction committed; not a JDBC outage");
        assertThat(storage.exists(committedObject)).isTrue();assertThat(upload(first,image,unknownUpload,unknownKey).path("data").path("assetId").asText()).isEqualTo(committedAssetId);
        assertThat(executions(c)).isZero();assertThat(executions(d)).isZero();assertThat(executions(unsent)).isZero();
        String batch=batch(first,List.of(c,d,unsent),"SERVICE","IMAGE","Shared private image caption",null,null,asset);send(batch,c);send(batch,d);cancel(first,batch);counts(batch,2,0,0,1,0);
        String cAttachment=row(batch,c).get("attachment_id").toString(),dAttachment=row(batch,d).get("attachment_id").toString();assertThat(cAttachment).isNotEqualTo(dAttachment);
        var refs=jdbc.queryForList("SELECT object_key,state,customer_id FROM nx_support_attachment WHERE id IN (?,?) ORDER BY customer_id",cAttachment,dAttachment);assertThat(refs).hasSize(2);assertThat(refs.get(0).get("object_key")).isEqualTo(refs.get(1).get("object_key"));assertThat(refs).allSatisfy(ref->assertThat(ref.get("state")).isEqualTo("ATTACHED"));String object=refs.get(0).get("object_key").toString();assertThat(storage.exists(object)).isTrue();
        String cToken=userToken(c),dToken=userToken(d),owner=token(first),other=token(second),privateAdmin="/api/admin/content/conversations/attachments/",privateApp="/api/app/support/attachments/";
        var cBytes=download(privateApp+cAttachment+"/content",cToken);var dBytes=download(privateApp+dAttachment+"/content",dToken);
        assertThat(cBytes.statusCode()).isEqualTo(200);assertThat(dBytes.statusCode()).isEqualTo(200);assertThat(cBytes.body()).isEqualTo(dBytes.body());
        var decoded=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(cBytes.body()));assertThat(decoded.getWidth()).isEqualTo(4);assertThat(decoded.getHeight()).isEqualTo(4);assertThat(decoded.getRGB(1,1)&0xFFFFFF).isEqualTo(0xCC5500);
        assertThat(cBytes.headers().firstValue("Cache-Control").orElse("")).contains("no-store");assertThat(cBytes.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        assertThat(download(privateApp+cAttachment+"/content",dToken).statusCode()).isEqualTo(404);assertThat(download(privateApp+dAttachment+"/content",cToken).statusCode()).isEqualTo(404);assertThat(download(privateAdmin+cAttachment+"/content",other).statusCode()).isEqualTo(404);assertThat(download(privateAdmin+cAttachment+"/content",owner).body()).isEqualTo(cBytes.body());
        transfer(second,c);assertThat(download(privateAdmin+cAttachment+"/content",owner).statusCode()).isEqualTo(404);assertThat(download(privateAdmin+cAttachment+"/content",other).body()).isEqualTo(cBytes.body());assertThat(download(privateAdmin+dAttachment+"/content",owner).body()).isEqualTo(dBytes.body());
        var restricted=http("GET",BASE+"/"+batch,owner,null,null);assertThat(restricted.path("data").path("contentRestricted").asBoolean()).isTrue();assertThat(http("GET",BASE+"/"+batch+"/recipients",owner,null,null).path("data").path("records")).hasSize(2);
        // Cancelling an unsent per-customer reference must not remove the common attached object.
        String ready=UUID.randomUUID().toString();SecurityContextHolder.clearContext();new TransactionTemplate(transactions).executeWithoutResult(status->attachments.materializeBulkForActor(asset,first,unsent,bindingMapper.current(unsent).id(),ready));
        assertThat(http("DELETE",privateAdmin+ready,owner,null,key()).path("code").asInt()).isZero();assertThat(storage.exists(object)).isTrue();assertThat(download(privateApp+dAttachment+"/content",dToken).body()).isEqualTo(dBytes.body());
        String expiredImageSelection=preview(first,List.of(unsent),List.of(),"SINGLE",null).path("selectionId").asText();
        jdbc.update("UPDATE nx_support_bulk_job SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",committedAssetId);
        var expiredImage=http("POST",BASE,owner,new SupportBulkRequest.Create(expiredImageSelection,"SERVICE","IMAGE","Expired asset must not send",null,null,committedAssetId,"Reject actual UTC asset expiry"),key());
        assertThat(expiredImage.path("code").asInt()).as("Near-now asset expiry: %s",expiredImage.path("message")).isEqualTo(409);assertThat(messageCount(unsent)).isZero();
        jdbc.update("UPDATE nx_support_bulk_job SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",asset);attachments.cleanupExpiredBulkAssets();
        assertThat(jdbc.queryForObject("SELECT state FROM nx_support_bulk_job WHERE id=?",String.class,asset)).isEqualTo("EXPIRED");assertThat(storage.exists(object)).isTrue();assertThat(download(privateApp+cAttachment+"/content",cToken).body()).isEqualTo(cBytes.body());
        String originalBucket=storageProperties.getBucket(),failedUpload=key(),failedCommand=key(),missingBucket="bulk-unavailable-"+run.replace('_','-');
        var missingBucketIntent=objectRequest(SupportObjectEvidenceLedger.Kind.BULK_ASSET,first,null,null,failedUpload,failedCommand,null,missingBucket,true);
        try {storageProperties.setBucket(missingBucket);assertThat(upload(first,image,failedUpload,failedCommand,missingBucketIntent).path("code").asInt()).isNotZero();}
        finally {storageProperties.setBucket(originalBucket);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_support_bulk_job WHERE record_type='ASSET' AND actor_id=? AND client_upload_id=?",Long.class,first,failedUpload)).isZero();
        assertThat(executions(c)).isZero();assertThat(executions(d)).isZero();assertThat(executions(unsent)).isZero();assertThat(messageCount(unsent)).isZero();
        proof("B11","oneImageOwnsIsolatedPrivateReferencesAndRetainsCommittedBytes","One real multipart upload yields two distinct ATTACHED customer references to identical private bytes; cross-customer and foreign-advisor byte APIs deny. Formal transfer revokes former advisor and gives current advisor read, other customer unchanged. Batch/reference cancellation and authoritative ASSET-header expiry retain common attached object. Raw upload ID cannot move to another command key, accepted key/body cannot change, and real missing-bucket upload leaves no asset row or maintenance execution. An actual upload commits real DB/object facts; invoking its captured compensation callback with STATUS_UNKNOWN retains bytes and original-key recovery returns original asset; no dropped-connection claim is made.");writeProof("bulk-runtime.json");
    }

    @Test @Order(8) void allMessageKindsReuseOriginalProtocolAndOldApiCannotTakeBulkIds() throws Exception {
        var catalog=http("GET","/api/admin/content/session-templates/runtime",token(first),null,null);assertThat(catalog.path("code").asInt()).isZero();
        var localizedRepository=context.getBean(ffdd.opsconsole.content.domain.I18nLearningRepository.class);
        com.fasterxml.jackson.databind.JsonNode template=null;
        for(var candidate:catalog.path("data").path("replyTemplates")) {
            String body=candidate.path("text").asText(),id=candidate.path("id").asText();
            var copy=localizedRepository.findPublishedMessagePair("conversation.template."+id.toLowerCase(Locale.ROOT));
            if("published".equals(candidate.path("status").asText()) && !body.isBlank() && body.length()<=400 && !body.contains("://") && !body.trim().startsWith("{")
                    && copy.isPresent() && body.trim().equals(copy.get().zh().trim()) && !copy.get().en().isBlank() && !copy.get().vi().isBlank()) {template=candidate;break;}
        }
        String templateEvidence="Existing truly published template and localization read through original authenticated runtime/list HTTP.";
        if(template==null) {
            String fixtureId="RT_BULK_"+UUID.randomUUID().toString().replace("-",""),fixtureText="客服批次实际模板 "+run;
            // Only new isolated fixture rows are seeded; this test does not claim HTTP publication acceptance.
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                var repository=context.getBean(ffdd.opsconsole.content.domain.SessionTemplateRepository.class);var now=LocalDateTime.now();
                repository.createReplyTemplate(fixtureId,new SessionReplyTemplateCreateRequest("advisor",fixtureText,"draft",run,"Isolated published-template fixture"),now);
                localizedRepository.saveMessagePair("conversation.template."+fixtureId.toLowerCase(Locale.ROOT),fixtureText,"Actual support batch template "+run,"Mẫu hỗ trợ cho đợt gửi "+run,"published",now);
                repository.updateReplyTemplateStatus(fixtureId,"published",now);
            });
            catalog=http("GET","/api/admin/content/session-templates/runtime",token(first),null,null);assertThat(catalog.path("code").asInt()).isZero();
            template=java.util.stream.StreamSupport.stream(catalog.path("data").path("replyTemplates").spliterator(),false).filter(row->fixtureId.equals(row.path("id").asText())).findFirst().orElseThrow();
            assertThat(template.path("text").asText()).isEqualTo(fixtureText);assertThat(template.path("status").asText()).isEqualTo("published");
            assertThat(localizedRepository.findPublishedMessagePair("conversation.template."+fixtureId.toLowerCase(Locale.ROOT))).get().satisfies(copy->{assertThat(copy.zh()).isEqualTo(fixtureText);assertThat(copy.en()).isNotBlank();assertThat(copy.vi()).isNotBlank();});
            templateEvidence="Only this batch's new published template and three-language pair were seeded through original repositories; original authenticated runtime/list HTTP re-read published facts before use. HTTP publication/outbox acceptance is outside this fixture.";
        }
        String text=template.path("text").asText(),templateId=template.path("id").asText();
        var templates=http("GET","/api/admin/content/session-templates/reply-templates?keyword="+java.net.URLEncoder.encode(text,java.nio.charset.StandardCharsets.UTF_8)+"&status=published&pageSize=100",token(boss),null,null);assertThat(templates.path("code").asInt()).isZero();assertThat(templates.toString()).contains(text,templateId);
        long templated=customer(first);String templateBatch=batch(first,List.of(templated),"SERVICE","TEXT",text,null,null,null);send(templateBatch,templated);counts(templateBatch,1,0,0,0,0);
        assertThat(jdbc.queryForObject("SELECT m.content FROM nx_conversation_message m JOIN nx_support_human_message h ON h.message_id=m.id WHERE h.customer_id=? AND h.actor_type='ADMIN'",String.class,templated)).isEqualTo(text);
        long productCustomer=customer(first),linkCustomer=customer(first);String product=sku("actual",true);
        String productBatch=batch(first,List.of(productCustomer),"SERVICE","SKU","Real shared SKU caption",product,null,null);send(productBatch,productCustomer);counts(productBatch,1,0,0,0,0);
        String linkBatch=batch(first,List.of(linkCustomer),"SERVICE","LINK","Real shared link caption",null,new SupportLinkTarget("WALLET",Map.of()),null);send(linkBatch,linkCustomer);counts(linkBatch,1,0,0,0,0);
        var skuView=http("GET","/api/app/support/conversations/"+row(productBatch,productCustomer).get("conversation_no"),userToken(productCustomer),null,null);assertThat(skuView.path("code").asInt()).isZero();assertThat(skuView.toString()).contains("\"kind\":\"SKU\"",product,"Real shared SKU caption","AVAILABLE");
        var linkView=http("GET","/api/app/support/conversations/"+row(linkBatch,linkCustomer).get("conversation_no"),userToken(linkCustomer),null,null);assertThat(linkView.path("code").asInt()).isZero();assertThat(linkView.toString()).contains("\"kind\":\"LINK\"","WALLET","Real shared link caption");
        jdbc.update("UPDATE nx_product SET status='OFF_SALE',store_status='off' WHERE product_no=?",product);assertThat(http("GET","/api/app/support/conversations/"+row(productBatch,productCustomer).get("conversation_no"),userToken(productCustomer),null,null).toString()).contains("UNAVAILABLE");
        for(String state:List.of("CLOSED","TRANSFERRED","ARCHIVED","CONVERTED")) {
            long customer=customer(first);String initial=batch(first,List.of(customer),"SERVICE","TEXT","Lifecycle original",null,null,null);send(initial,customer);String no=row(initial,customer).get("conversation_no").toString();
            if("CONVERTED".equals(state)) {var header=jdbc.queryForMap("SELECT status,version FROM nx_conversation WHERE conversation_no=?",no);var converted=http("POST","/api/admin/content/conversations/"+no+"/ticket",token(first),new ConversationTicketRequest("other","NORMAL","Actual bulk lifecycle conversion",null,null,header.get("status").toString(),((Number)header.get("version")).longValue(),"Converted old segment proof",run),key());assertThat(converted.path("code").asInt()).isZero();}
            else if("ARCHIVED".equals(state))jdbc.update("UPDATE nx_conversation SET archived=1 WHERE conversation_no=?",no);
            else jdbc.update("UPDATE nx_conversation SET status=? WHERE conversation_no=?",state,no);
            long before=messageCount(customer),headers=jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation WHERE user_id=?",Long.class,customer);
            String blocked=batch(first,List.of(customer),"SERVICE","TEXT","Never create around closed old segment",null,null,null);send(blocked,customer);counts(blocked,0,0,1,0,0);assertThat(messageCount(customer)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation WHERE user_id=?",Long.class,customer)).isEqualTo(headers);
            assertThat(http("POST","/api/admin/content/conversations/"+no+"/replies",token(first),reply(customer,no,"Old API cannot reply blocked segment",key()),key()).path("code").asInt()).isEqualTo(409);
            String reserved=row(blocked,customer).get("client_message_id").toString();var forged=new ConversationInitiateRequest("advisor",customer,String.valueOf(second),"Spoofed input actor","Old API reserved bulk client", "Reject old API bulk bypass",run,"TEXT",null,"SERVICE",reserved,bindingMapper.current(customer).id(),List.of());
            assertThat(http("POST","/api/admin/content/conversations",token(first),forged,key()).path("code").asInt()).isEqualTo(422);assertThat(messageCount(customer)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation WHERE user_id=?",Long.class,customer)).isEqualTo(headers);assertThat(executions(customer)).isZero();
        }
        proof("B12","allMessageKindsReuseOriginalProtocolAndOldApiCannotTakeBulkIds",templateEvidence+" Published-list text becomes TEXT via shared message chain. SKU caption/id/name and typed WALLET destination reach authenticated app detail; down-sale projects UNAVAILABLE. Real IMAGE chain is independently asserted in B11. CLOSED/TRANSFERRED/ARCHIVED/converted advisor segments skip bulk without CREATE fallback; ordinary reply denies and old HTTP initiate cannot claim reserved bulk client ID, ignoring spoofed operator/owner inputs.");writeProof("bulk-runtime.json");
    }

    @Test @Order(9) void actualCustomerSocketAndAdvisorStreamReceiveCommittedBulkMessage() throws Exception {
        long customer=customer(first);String initial=batch(first,List.of(customer),"SERVICE","TEXT","Realtime original header",null,null,null);send(initial,customer);String no=row(initial,customer).get("conversation_no").toString();String client=userToken(customer),owner=token(first);
        try(var customerSocket=socket(client,true);var oldSocket=socket(owner,false);var oldStream=stream(owner)) {
            customerSocket.send(Map.of("type","watch","conversationNo",no));customerSocket.await("presence");oldSocket.send(Map.of("type","watch","conversationNo",no));oldSocket.await("presence");
            String body="Actual realtime bulk message "+run;String batch=batch(first,List.of(customer),"SERVICE","TEXT",body,null,null,null);send(batch,customer);long message=((Number)row(batch,customer).get("message_id")).longValue();
            assertThat(customerSocket.await("event").path("conversationNo").asText()).isEqualTo(no);var actual=oldStream.awaitMessage(message);assertThat(actual.path("conversationNo").asText()).isEqualTo(no);assertThat(actual.path("body").asText()).isEqualTo(body);
            var detail=http("GET","/api/app/support/conversations/"+no,client,null,null);assertThat(detail.path("code").asInt()).isZero();assertThat(message(detail.path("data").path("messages"),message).path("content").asText()).isEqualTo(body);
            transfer(second,customer);oldSocket.await("scope-invalidated");oldStream.awaitLine("event:scope-invalidated");oldSocket.send(Map.of("type","watch","conversationNo",no));assertThat(oldSocket.await("error").path("code").asInt()).isEqualTo(404);
            assertThat(http("GET","/api/admin/content/conversations/"+no,owner,null,null).path("code").asInt()).isEqualTo(404);
            oldStream.lines.clear();customerSocket.frames.clear();
            try(var currentStream=stream(token(second))) {
                String nextBody="Realtime after formal transfer "+run;String next=batch(second,List.of(customer),"SERVICE","TEXT",nextBody,null,null,null);send(next,customer);long nextMessage=((Number)row(next,customer).get("message_id")).longValue();
                assertThat(currentStream.awaitMessage(nextMessage).path("body").asText()).isEqualTo(nextBody);assertThat(customerSocket.await("event").path("conversationNo").asText()).isEqualTo(no);
                assertThat(oldStream.lines).noneMatch(line->line.contains(nextBody));assertThat(oldStream.messageIds()).doesNotContain(nextMessage);
            }
        }
        proof("X02","actualCustomerSocketAndAdvisorStreamReceiveCommittedBulkMessage","Real authenticated customer WS receives production conversation invalidation and authorized app refresh reads actual committed message ID/body; advisor SSE receives the identical committed messageId. Formal transfer revokes old WS watch/detail and old SSE receives no later private message while current advisor SSE and customer WS receive it. WS intentionally carries invalidation only per existing production contract.");writeProof("bulk-runtime.json");
    }

    @Test @Order(10) void oneHundredUnresolvedOrphansCannotStarveNewEligibleDelivery() throws Exception {
        long corrupted=customer(first),normal=customer(first);long assignment=bindingMapper.current(corrupted).id();var jobs=new ArrayList<String>();var clients=new ArrayList<String>();var messageIds=new ArrayList<Long>();
        long conflicted=customer(first);String conflictedBatch=batch(first,List.of(conflicted),"SERVICE","TEXT","Actual prepared fact requires exact original hash",null,null,null);send(conflictedBatch,conflicted);
        var committed=row(conflictedBatch,conflicted);long committedMessage=((Number)committed.get("message_id")).longValue();String originalDto=committed.get("request_json").toString();
        String originalHash=jdbc.queryForObject("SELECT payload_hash FROM nx_support_human_message WHERE message_id=?",String.class,committedMessage);
        jdbc.update("UPDATE nx_support_human_message SET payload_hash=? WHERE message_id=?","0".repeat(64),committedMessage);
        jdbc.update("UPDATE nx_support_bulk_recipient SET state='PENDING',result_certainty='UNKNOWN',message_id=NULL WHERE batch_id=?",conflictedBatch);
        jdbc.update("UPDATE nx_support_bulk_job SET state='QUEUED',created_at='1999-12-31 00:00:00' WHERE id=?",conflictedBatch);
        long baseId=jdbc.queryForObject("SELECT GREATEST((SELECT COALESCE(MAX(message_id),0) FROM nx_support_human_message),(SELECT COALESCE(MAX(id),0) FROM nx_conversation_message))+1000000",Long.class);
        try {
            for(int index=0;index<100;index++) {
                String id=UUID.randomUUID().toString(),client="bulk_orphan_"+UUID.randomUUID().toString().replace("-","");long message=baseId+index;jobs.add(id);clients.add(client);messageIds.add(message);
                // Deliberate durable corruption, confined to this fixture's exact IDs; no fake worker or authorizer.
                jdbc.update("INSERT INTO nx_support_bulk_job(id,record_type,actor_id,state,selection_mode,filters_json,excluded_json,content_json,frozen_count,created_at,updated_at) VALUES(?,'JOB',?,'QUEUED','EXPLICIT','null','[]',?,1,'2000-01-01 00:00:00','2000-01-01 00:00:00')",id,first,json.writeValueAsString(new SupportBulkRequest.Create(id,"SERVICE","TEXT","Unresolved fixture fact",null,null,null,"Orphan review fixture")));
                jdbc.update("INSERT INTO nx_support_bulk_recipient(batch_id,customer_id,expected_assignment_id,client_message_id,operation,state,result_certainty,failure_code,created_at,updated_at) VALUES(?,?,?,?,'CREATE','PENDING','UNKNOWN','RESULT_UNKNOWN','2000-01-01 00:00:00','2000-01-01 00:00:00')",id,corrupted,assignment,client);
                jdbc.update("INSERT INTO nx_support_human_message(message_id,customer_id,assignment_id,actor_type,actor_id,client_message_id,kind,intent,committed_at,payload_hash) VALUES(?,?,?,'ADMIN',?,?,'TEXT','SERVICE',UTC_TIMESTAMP(6),?)",message,corrupted,assignment,first,client,"0".repeat(64));
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM nx_conversation_message WHERE id>=? AND id<?",Long.class,baseId,baseId+100)).isZero();
            String ordinary=batch(first,List.of(normal),"SERVICE","TEXT","New normal task behind old review backlog",null,null,null);SecurityContextHolder.clearContext();bulk.runPending();bulk.runPending();
            counts(ordinary,1,0,0,0,0);assertThat(messageCount(normal)).isEqualTo(1);assertThat(executions(normal)).isZero();
            for(String id:jobs) {var uncertain=row(id,corrupted);assertThat(uncertain.get("state")).isEqualTo("PENDING");assertThat(uncertain.get("result_certainty")).isEqualTo("UNKNOWN");assertThat(uncertain.get("request_json")).isNull();assertThat(uncertain.get("message_id")).isNull();assertThat(uncertain.get("failure_code")).isEqualTo("SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED");counts(id,0,0,0,0,1);}
            var conflict=row(conflictedBatch,conflicted);assertThat(conflict.get("state")).isEqualTo("PENDING");assertThat(conflict.get("result_certainty")).isEqualTo("UNKNOWN");assertThat(conflict.get("failure_code")).isEqualTo("SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED");assertThat(conflict.get("request_json")).hasToString(originalDto);
            cancel(first,conflictedBatch);send(conflictedBatch,conflicted);counts(conflictedBatch,0,0,0,0,1);assertThat(row(conflictedBatch,conflicted).get("failure_code")).isEqualTo("SUPPORT_BULK_PAYLOAD_REVIEW_REQUIRED");assertThat(messageCount(conflicted)).isEqualTo(1);assertThat(executions(conflicted)).isZero();
            bulk.runPending();assertThat(messageCount(normal)).isEqualTo(1);assertThat(executions(corrupted)).isZero();
            proof("X03","oneHundredUnresolvedOrphansCannotStarveNewEligibleDelivery","One hundred actual old PENDING/UNKNOWN rows with missing DTO and orphan metadata remain unresolved and never create messages/executions. A real committed message with its prepared DTO but injected conflicting metadata hash also stays manual-review UNKNOWN through explicit cancel/send and never duplicates. A newer eligible batch completes within two real scans and repeated scan does not duplicate its message; no unknown row is relabelled FAILED/CANCELLED to obtain progress.");writeProof("bulk-runtime.json");
        } finally {
            jdbc.update("UPDATE nx_support_human_message SET payload_hash=? WHERE message_id=?",originalHash,committedMessage);send(conflictedBatch,conflicted);counts(conflictedBatch,1,0,0,0,0);assertThat(row(conflictedBatch,conflicted).get("message_id")).isEqualTo(committedMessage);assertThat(messageCount(conflicted)).isEqualTo(1);
            for(int index=0;index<jobs.size();index++) {
                // Remove only introduced corrupt metadata, then ordinary cancellation can prove absence and finish.
                jdbc.update("DELETE FROM nx_support_human_message WHERE message_id=? AND actor_id=? AND client_message_id=?",messageIds.get(index),first,clients.get(index));cancel(first,jobs.get(index));
            }
        }
    }

    @Test @Order(99) void persistQueuedAndPreparedRestartSeedAsLastFirstJvmAction() throws Exception {
        long actor=admin("restart","SUPPORT","DEDICATED"),one=customer(actor),two=customer(actor),prepared=customer(actor);retainedAdmins.add(actor);
        String batch=batch(actor,List.of(one,two),"MAINTENANCE","TEXT","Second JVM durable frozen delivery",null,null,null);
        String preparedBatch=batch(actor,List.of(prepared),"MAINTENANCE","TEXT","Prepared DTO survives JVM exit",null,null,null);SecurityContextHolder.clearContext();bulk.prepareRecipient(preparedBatch,prepared);
        counts(batch,0,0,0,0,2);counts(preparedBatch,0,0,0,0,1);assertThat(jdbc.queryForObject("SELECT state FROM nx_support_bulk_job WHERE id=?",String.class,batch)).isEqualTo("QUEUED");assertThat(row(preparedBatch,prepared).get("request_json")).isNotNull();
        var seed=new LinkedHashMap<String,Object>();seed.put("checkedAt",Instant.now().toString());seed.put("database",SupportRuntimeTarget.current().database());seed.put("port",SupportRuntimeTarget.current().httpPort());seed.put("workflowRunId",System.getenv("WORKFLOW_RUN_ID"));seed.put("snapshotHash",System.getenv("WORKFLOW_SNAPSHOT_HASH"));seed.put("firstJvmPid",ProcessHandle.current().pid());seed.put("actorId",actor);seed.put("batchId",batch);seed.put("customerIds",List.of(one,two));seed.put("clientMessageIds",List.of(row(batch,one).get("client_message_id"),row(batch,two).get("client_message_id")));seed.put("frozenContent",jdbc.queryForObject("SELECT content_json FROM nx_support_bulk_job WHERE id=?",String.class,batch));seed.put("preparedBatchId",preparedBatch);seed.put("preparedCustomerId",prepared);seed.put("preparedClientMessageId",row(preparedBatch,prepared).get("client_message_id"));seed.put("preparedRequestJson",row(preparedBatch,prepared).get("request_json"));
        assertThat(messageCount(one)+messageCount(two)+messageCount(prepared)).isZero();
        fixtureActors().deferCleanup(actor,"SupportBulkRestartRuntimeTest");
        seed.put("actorCreationProof",fixtureActors().creationReference(actor));
        Files.writeString(Path.of(System.getenv("CS_ENHANCE_EVIDENCE_DIR"),"bulk-restart-seed.json"),json.writeValueAsString(seed));writeProof("bulk-runtime.json");
    }

    private com.fasterxml.jackson.databind.JsonNode message(com.fasterxml.jackson.databind.JsonNode rows,long id) {for(var row:rows)if(row.path("id").asLong()==id)return row;throw new AssertionError("Actual message missing "+id);}
    private SocketProbe socket(String token,boolean app) throws Exception {
        var ticket=http("POST",app?"/api/app/support/realtime-ticket":"/api/admin/content/conversations/realtime-ticket",token,null,null);assertThat(ticket.path("code").asInt()).isZero();var probe=new SocketProbe();
        probe.socket=java.net.http.HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(java.net.URI.create(SupportRuntimeTarget.current().websocketBase()+"/ws/conversations"),probe).get(10,TimeUnit.SECONDS);probe.send(Map.of("type","auth","ticket",ticket.path("data").path("ticket").asText()));probe.await("ready");return probe;
    }
    private final class SocketProbe implements java.net.http.WebSocket.Listener,AutoCloseable {
        java.net.http.WebSocket socket;final BlockingQueue<String> frames=new LinkedBlockingQueue<>();final StringBuilder buffer=new StringBuilder();
        public void onOpen(java.net.http.WebSocket socket) {socket.request(1);}
        public CompletionStage<?> onText(java.net.http.WebSocket socket,CharSequence data,boolean last) {buffer.append(data);if(last){frames.add(buffer.toString());buffer.setLength(0);}socket.request(1);return null;}
        void send(Object value) throws Exception {socket.sendText(json.writeValueAsString(value),true).get(5,TimeUnit.SECONDS);}
        com.fasterxml.jackson.databind.JsonNode await(String type) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){String value=frames.poll(200,TimeUnit.MILLISECONDS);if(value!=null){var frame=json.readTree(value);if(type.equals(frame.path("type").asText()))return frame;}}throw new AssertionError("Actual WS event missing "+type);}
        public void close() {socket.abort();}
    }
    private StreamProbe stream(String token) throws Exception {
        var response=java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(SupportRuntimeTarget.current().httpBase()+"/api/admin/content/conversations/stream")).header("Authorization","Bearer "+token).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofInputStream());assertThat(response.statusCode()).isEqualTo(200);return new StreamProbe(response.body());
    }
    private final class StreamProbe implements AutoCloseable {
        final java.io.InputStream input;final List<String> lines=new CopyOnWriteArrayList<>();
        StreamProbe(java.io.InputStream input) {this.input=input;Thread reader=new Thread(()->{try(var text=new java.io.BufferedReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=text.readLine())!=null)lines.add(line);}catch(java.io.IOException ignored){/* Closing this owned test subscription ends its reader. */}finally{lines.add("CLOSED");}},"bulk-runtime-sse-reader");reader.setDaemon(true);reader.start();}
        void awaitLine(String value) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){if(lines.contains(value))return;Thread.sleep(25);}throw new AssertionError("Actual SSE event missing "+value);}
        com.fasterxml.jackson.databind.JsonNode awaitMessage(long messageId) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<until){for(String line:lines)if(line.startsWith("data:")){var event=json.readTree(line.substring(5));if(event.path("messageId").asLong()==messageId)return event;}Thread.sleep(25);}throw new AssertionError("Actual SSE message missing "+messageId);}
        List<Long> messageIds() throws Exception {var ids=new ArrayList<Long>();for(String line:lines)if(line.startsWith("data:")){var event=json.readTree(line.substring(5));if(event.path("messageId").isNumber())ids.add(event.path("messageId").asLong());}return ids;}
        public void close() throws Exception {input.close();}
    }
    private void await(CountDownLatch latch) {try{if(!latch.await(20,TimeUnit.SECONDS))throw new IllegalStateException("Isolated row lock release missing");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
    private void awaitJobHeld(String batch) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            try(var connection=jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                try(var query=connection.prepareStatement("SELECT id FROM nx_support_bulk_job WHERE id=? FOR UPDATE NOWAIT")) {query.setString(1,batch);query.executeQuery();}
                catch(SQLException ex) {if(ex.getErrorCode()==3572)return;throw ex;}
                finally {connection.rollback();}
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Worker did not hold actual job row");
    }
    private String sku(String label,boolean on) {String id="bulk-"+run.substring(5)+"-"+label;jdbc.update("INSERT INTO nx_product(product_no,name,product_type,status,store_status,price_usdt,estimated_daily_usdt,stock) VALUES(?,?,'DEVICE',?,?,10,0.1,100)",id,"Actual bulk SKU "+label,on?"ON_SALE":"OFF_SALE",on?"on":"off");return id;}
    private List<Long> ids(com.fasterxml.jackson.databind.JsonNode rows) {var result=new ArrayList<Long>();for(var row:rows)result.add(row.path("id").asLong());return result;}
    private SupportBulkRequest.Filters filter(String account,String maintenance,String level,List<String>tags,String registeredFrom,String registeredTo,String activityFrom,String activityTo,String depositMin,String depositMax,String withdrawalMin,String withdrawalMax,Boolean unknown,String keyword) {
        return new SupportBulkRequest.Filters(account,maintenance,level,tags,registeredFrom,registeredTo,activityFrom,activityTo,depositMin,depositMax,withdrawalMin,withdrawalMax,depositMin!=null || depositMax!=null || withdrawalMin!=null || withdrawalMax!=null?"USDT":null,unknown,keyword);
    }
    private void effective(long customer) {new TransactionTemplate(transactions).executeWithoutResult(status->activity.interactiveLogin(customer,UUID.randomUUID().toString()));}
    private void deposit(long customer,String asset,String amount) {
        String no=key();var value=new java.math.BigDecimal(amount);
        jdbc.update("INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,created_at) VALUES(?,?,'CHAIN_TOPUP',?,'IN',?,0,'SUCCESS',NOW())",customer,no,asset,value);
        long ledger=jdbc.queryForObject("SELECT id FROM nx_wallet_ledger WHERE user_id=? AND biz_no=?",Long.class,customer,no);
        jdbc.update("INSERT INTO nx_deposit_order(user_id,deposit_no,chain_name,chain_tx_hash,asset,amount,status,ledger_id,credited_at,created_at) VALUES(?,?,'TRC20',?,?,?,'SUCCESS',?,NOW(),NOW())",customer,no,no,asset,value,ledger);
    }
    private void withdrawal(long customer,String amount) {jdbc.update("INSERT INTO nx_withdrawal_order(user_id,withdrawal_no,asset,amount,target_address,status,d2_penalty_fee_rate,d2_gross_fee,d2_nex_burned,d2_nex_fee_offset_rate,d2_fee_waived,d2_actual_fee,d2_net_receive,completed_at,created_at) VALUES(?,?,'USDT',?,'isolated-address','SUCCESS',0,0,0,0,0,0,?,NOW(),NOW())",customer,key(),new java.math.BigDecimal(amount),new java.math.BigDecimal(amount));}
    private ConversationReplyRequest reply(long customer,String no,String body,String client) {
        var header=jdbc.queryForMap("SELECT status,version FROM nx_conversation WHERE conversation_no=?",no);
        return new ConversationReplyRequest(body,header.get("status").toString(),((Number)header.get("version")).longValue(),"Ordinary actual help proof","spoofed-input-author",List.of(),null,"TEXT",null,"SERVICE",client,bindingMapper.current(customer).id());
    }
    private void assertAuthor(String batch,long customer,long actor) {
        var row=row(batch,customer);assertThat(row.get("state")).isEqualTo("SENT");long message=((Number)row.get("message_id")).longValue();String no=row.get("conversation_no").toString();
        assertThat(jdbc.queryForObject("SELECT sender_id FROM nx_conversation_message WHERE id=?",Long.class,message)).isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT sender_name FROM nx_conversation_message WHERE id=?",String.class,message)).isEqualTo("admin:"+actor);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM nx_support_human_message WHERE message_id=?",Long.class,message)).isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT agent_admin_id FROM nx_support_maintenance_execution WHERE message_id=?",Long.class,message)).isEqualTo(actor);
        assertThat(jdbc.queryForList("SELECT actor_id FROM nx_audit_log WHERE resource_id=? AND action IN ('I9_CONVERSATION_INITIATED','I9_CONVERSATION_REPLIED')",Long.class,no)).containsOnly(actor).isNotEmpty();
    }
}
