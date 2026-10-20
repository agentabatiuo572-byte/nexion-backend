package ffdd.opsconsole.onboarding.mapper;

import static org.assertj.core.api.Assertions.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.onboarding.application.PhoneReplacementPolicy;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.*;

/** SPEC07 S14/S20/S23: sequential domain transactions, real mapper/A2/A4, controlled server clock.
 * Reuses only the existing owned-schema fixture; does not inherit or rerun its test methods. */
@EnabledIfEnvironmentVariable(named="NEXION_TEST_DB_PASSWORD",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhoneReplacementSequenceMySqlTest {
    final PhoneReplacementAtomicityMySqlTest f=new PhoneReplacementAtomicityMySqlTest();
    boolean failed;
    @RegisterExtension final TestWatcher result=new TestWatcher(){
        @Override public void testFailed(ExtensionContext c,Throwable t){failed=true;}
    };
    @BeforeAll void setup() throws Exception {f.setup();}
    @BeforeEach void reset(){f.defaults();}
    @AfterAll void cleanup() throws Exception {
        if(f.admin==null)return;
        try {
            if(!failed){f.admin.createStatement().execute("DROP DATABASE `"+f.schema+"`");System.out.println("PHONE_SEQUENCE_FIXTURE_DROP="+f.schema);}
            else System.out.println("PHONE_SEQUENCE_FIXTURE_RETAINED="+f.schema);
        } finally {f.admin.close();}
    }
    @Test void firstBindingStartsTheIntervalWithoutCountingAndSameBindingRecoveryDoesNotResetIt(){
        Account a=f.account(7101);
        f.jdbc.update("UPDATE nx_config_item SET config_value='1' WHERE config_key=?",PhoneReplacementPolicy.PREFIX+"minReplacementIntervalDays");
        Receipt first=f.activate(a,"A",null,null,"first-7101");
        long start=f.mapper.binding(a).changedAt();
        assertThat(f.count("nx_phone_replacement_event",a)).isZero();
        var eligibility=f.service.eligibility(a,"B","hw",true,List.of());
        assertThat(eligibility.usedCount()).isZero();
        assertThat(eligibility.nextAllowedAt()).isEqualTo(start+PhoneReplacementPolicy.DAY);
        assertThat(eligibility.blockedReasons()).contains("PHONE_REPLACEMENT_COOLDOWN");
        f.now.set(start+PhoneReplacementPolicy.DAY-1);
        assertThatThrownBy(()->f.activate(a,"B",1L,null,"too-soon-7101")).hasMessage("PHONE_REPLACEMENT_COOLDOWN");
        assertThat(f.mapper.binding(a).installationId()).isEqualTo("A");
        assertThat(f.count("nx_phone_replacement_event",a)).isZero();
        f.activate(a,"A",1L,null,"restore-7101");
        assertThat(f.mapper.binding(a).changedAt()).isEqualTo(start);
        assertThat(f.count("nx_phone_replacement_event",a)).isZero();
        assertThat(f.service.eligibility(a,"B","hw",true,List.of()).nextAllowedAt()).isEqualTo(start+PhoneReplacementPolicy.DAY);
        f.now.set(start+PhoneReplacementPolicy.DAY);
        assertThat(f.service.eligibility(a,"B","hw",true,List.of()).canReplace()).isTrue();
        f.activate(a,"B",2L,null,"boundary-7101");
        assertThat(f.count("nx_phone_replacement_event",a)).isEqualTo(1);
        assertThat(f.mapper.binding(a).changedAt()).isEqualTo(start+PhoneReplacementPolicy.DAY);
        assertThat(f.activate(a,"A",null,null,"first-7101").originalResult()).isEqualTo(first.originalResult());
    }
    @Test void failedActivationDoesNotConsumeAndTheSameFirstExecutionCannotUseTheGrantAfterExpiry(){
        Account a=f.account(7201);f.activate(a,"A",null,null,"first-7201");
        Request request=f.request(a,"B","request-7201");Receipt issue=f.issue(a,request,"issue-7201");
        GrantSummary grant=(GrantSummary)issue.currentResource();
        f.failAudit.set("PHONE_REPLACEMENT_COMMAND");
        assertThatThrownBy(()->f.activate(a,"B",1L,grant.grantId(),"consume-7201")).hasMessage("injected audit failure");
        assertThat(f.mapper.grant(a,grant.grantId()).status()).isEqualTo("ACTIVE");
        assertThat(f.mapper.grant(a,grant.grantId()).consumedAt()).isNull();
        assertThat(f.mapper.binding(a).installationId()).isEqualTo("A");
        assertThat(f.count("nx_phone_replacement_event",a)).isZero();
        assertThat(f.service.operationByKey(a,new Actor("USER",a.userId()),"PHONE_ACTIVATE","consume-7201")).isNull();
        f.failAudit.set(null);f.now.set(grant.expiresAt()+1);
        assertThat(f.service.currentRequest(a,"B").grant().status()).isEqualTo("EXPIRED");
        assertThatThrownBy(()->f.activate(a,"B",1L,grant.grantId(),"consume-7201")).hasMessage("GRANT_STATE_CHANGED");
        assertThat(f.mapper.grant(a,grant.grantId()).consumedAt()).isNull();
        assertThat(f.mapper.binding(a).installationId()).isEqualTo("A");
        assertThat(f.count("nx_phone_replacement_event",a)).isZero();
        Receipt replay=f.issue(a,request,"issue-7201");
        assertThat(replay.originalResult()).isEqualTo(issue.originalResult());
        assertThat(((GrantSummary)replay.currentResource()).expiresAt()).isEqualTo(grant.expiresAt());
        assertThat(((GrantSummary)replay.currentResource()).status()).isEqualTo("EXPIRED");
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='admin.phone_replacement_grant_consumed' AND aggregate_id=?",Integer.class,grant.grantId())).isZero();
    }
    @Test void twoDifferentConsumptionCommandsHaveOneCommittedGrantBindingAndReplacementEvent() throws Exception {
        Account a=f.account(7301);f.activate(a,"A",null,null,"first-7301");
        Request request=f.request(a,"B","request-7301");Receipt issue=f.issue(a,request,"issue-7301");
        String grant=((GrantSummary)issue.currentResource()).grantId();
        var results=f.race(()->f.activate(a,"B",1L,grant,"consume-left-7301"),()->f.activate(a,"B",1L,grant,"consume-right-7301"));
        assertThat(results.stream().filter(Receipt.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(Throwable.class::isInstance)).hasSize(1);
        Receipt winner=(Receipt)results.stream().filter(Receipt.class::isInstance).findFirst().orElseThrow();
        assertThat(winner.status()).isEqualTo("SUCCEEDED");
        assertThat(f.mapper.grant(a,grant).status()).isEqualTo("CONSUMED");
        assertThat(f.mapper.grant(a,grant).consumedOperationId()).isEqualTo(winner.operationId());
        assertThat(f.mapper.binding(a).installationId()).isEqualTo("B");
        assertThat(f.mapper.binding(a).version()).isEqualTo(2);
        assertThat(f.count("nx_phone_replacement_event",a)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_event_outbox WHERE event_name='admin.phone_replacement_grant_consumed' AND aggregate_id=?",Integer.class,grant)).isEqualTo(1);
        assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM nx_phone_replacement_operation WHERE user_id=? AND idempotency_key IN ('consume-left-7301','consume-right-7301') AND status='SUCCEEDED'",Integer.class,a.userId())).isEqualTo(1);
        assertThat(f.issue(a,request,"issue-7301").originalResult()).isEqualTo(issue.originalResult());
    }
}
