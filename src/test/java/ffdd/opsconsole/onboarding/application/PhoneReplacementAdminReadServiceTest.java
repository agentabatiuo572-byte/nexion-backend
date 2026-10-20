package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.common.boundary.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.growth.application.WheelSandboxProfile;
import ffdd.opsconsole.onboarding.facade.PhoneReplacementFacade;
import ffdd.opsconsole.onboarding.mapper.OnboardingCalibrationMapper;
import ffdd.opsconsole.onboarding.mapper.PhoneReplacementMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.CurrentAppSessionService;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import ffdd.opsconsole.user.application.OpsUserService;
import ffdd.opsconsole.user.application.PhoneReplacementOperationsService;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.*;
import ffdd.opsconsole.user.mapper.PhoneReplacementVerificationMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Adapter composition test: real account projection/runtime, mocked persistence and authorization boundaries. */
class PhoneReplacementAdminReadServiceTest {
    static final long USER=71,ADMIN=900,NOW=1_800_000_000_000L;
    static final String REASON="Frozen account safety action";
    final Clock clock=Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC);
    final MockEnvironment environment=new MockEnvironment();
    final PhoneReplacementMapper mapper=mock(PhoneReplacementMapper.class);
    final OnboardingCalibrationMapper calibration=mock(OnboardingCalibrationMapper.class);
    final PhoneReplacementService projectionDomain=mock(PhoneReplacementService.class);
    final OnboardingCalibrationService onboarding=mock(OnboardingCalibrationService.class);
    final CurrentAppSessionService sessions=mock(CurrentAppSessionService.class);
    final PhoneReplacementAdminReadService views=new PhoneReplacementAdminReadService(projectionDomain,mapper,calibration,
            onboarding,new WheelSandboxProfile(environment),environment,clock,sessions);
    final PhoneReplacementAdminAccess access=mock(PhoneReplacementAdminAccess.class);
    final OpsUserService users=mock(OpsUserService.class);
    final PhoneReplacementVerificationMapper evidence=mock(PhoneReplacementVerificationMapper.class);
    final AuditLogService audit=mock(AuditLogService.class);

    @BeforeEach void setup(){
        environment.setActiveProfiles("test");environment.setProperty("nexion.wheel.sandbox.run-id","frozen-scope-run");
        when(mapper.adminUserSandbox(USER)).thenReturn(1);
        // The old ACTIVE-only selector has no row for this frozen account.
        when(calibration.userSandbox(USER)).thenReturn(null);
        when(access.require(PhoneReplacementOperationsService.GRANT)).thenReturn(ADMIN);
        when(access.require(PhoneReplacementOperationsService.REVOKE)).thenReturn(ADMIN);
        when(access.require(PhoneReplacementOperationsService.GRANT,PhoneReplacementOperationsService.REVOKE)).thenReturn(ADMIN);
        when(users.profile(USER)).thenReturn(ApiResult.ok(new UserAccountView(USER,"U00000071","Fixture",null,null,"FROZEN",null,null,
                false,null,null,null,null,0L,0L,null,null)));
    }
    @AfterEach void cleanup(){TransactionSynchronizationManager.setActualTransactionActive(false);}
    PhoneReplacementOperationsService operations(PhoneReplacementContract domain){
        return new PhoneReplacementOperationsService(access,users,domain,views,mock(PhoneReplacementPolicyOperations.class),
                mock(PhoneReplacementSupportContract.class),evidence,audit,clock);
    }
    Account account(){return new Account(USER,"SANDBOX","frozen-scope-run");}
    Operation operation(String id,String status){return new Operation(id,USER,"SANDBOX","frozen-scope-run","ADMIN",ADMIN,
            "PHONE_GRANT_ISSUE","original-key","hash",status,NOW-1000,"SUCCEEDED".equals(status)?NOW-900:null,
            "SUCCEEDED".equals(status)?"{}":null,"GRANT","grant","audit");}

    @ParameterizedTest @CsvSource({"test,1,SANDBOX","dev,0,PRODUCTION"})
    void frozenAccountResolvesAudienceWithoutCallingActiveOnlyCalibrationSelector(String profile,int sandbox,String expected){
        environment.setActiveProfiles(profile);when(mapper.adminUserSandbox(USER)).thenReturn(sandbox);
        Account resolved=views.account(USER);
        assertThat(resolved.sourceEnvironment()).isEqualTo(expected);
        assertThat(resolved.runId()).isEqualTo("SANDBOX".equals(expected)?"frozen-scope-run":"");
        verify(calibration,never()).userSandbox(anyLong());
    }
    @ParameterizedTest @CsvSource({"test,0","dev,1"})
    void oppositeAudienceRemainsUnavailable(String profile,int sandbox){
        environment.setActiveProfiles(profile);when(mapper.adminUserSandbox(USER)).thenReturn(sandbox);
        assertThatThrownBy(()->views.account(USER)).hasMessage("USER_NOT_FOUND");
    }
    @Test void deletedOrMissingAccountCannotResolveScopeOrReachAdministrativeDomain(){
        when(mapper.adminUserSandbox(USER)).thenReturn(null);
        var domain=mock(PhoneReplacementContract.class);var operations=operations(domain);
        assertThatThrownBy(()->views.account(USER)).hasMessage("USER_NOT_FOUND");
        assertThatThrownBy(()->operations.revoke(USER,"grant",new Confirm(REASON,true),"revoke-key")).hasMessage("USER_NOT_FOUND");
        assertThatThrownBy(()->operations.close(USER,"request",new Confirm(REASON,true),"close-key")).hasMessage("USER_NOT_FOUND");
        verifyNoInteractions(domain);
    }
    @Test void frozenProfilePassesRealAccountAdapterForRevokeCloseAndOriginalReceipt(){
        var domain=mock(PhoneReplacementContract.class);var operations=operations(domain);
        var receipt=new Receipt("PRO-original","PHONE_GRANT_ISSUE","SUCCEEDED",NOW-1000,NOW-900,
                Map.of("grantId","grant"),Map.of("status","REVOKED"),"audit");
        when(domain.adminOperationContext("PRO-original")).thenReturn(operation("PRO-original","SUCCEEDED"));
        when(domain.operation(account(),"PRO-original")).thenReturn(receipt);
        operations.revoke(USER,"grant",new Confirm(REASON,true),"revoke-key");
        operations.close(USER,"request",new Confirm(REASON,true),"close-key");
        assertThat(operations.operation("PRO-original")).isSameAs(receipt);
        verify(domain).revokeGrant(account(),ADMIN,"grant",REASON,"revoke-key");
        verify(domain).closeRequest(account(),ADMIN,"request",REASON,"close-key");
        verify(domain).operation(account(),"PRO-original");
        verify(calibration,never()).userSandbox(anyLong());
    }
    @Test void overviewLocksPolicyAccountAndBindingBeforeSharedEligibilityAndUsesCurrentBinding(){
        Binding current=new Binding("installation-B",72L,"hw",NOW,5);
        Binding oldSnapshot=new Binding("installation-A",71L,"old-hw",NOW-1000,4);
        when(mapper.lockBinding(account())).thenReturn(current);
        when(mapper.binding(account())).thenReturn(oldSnapshot); // A prior RR snapshot must not drive pause lookup.
        when(sessions.adminSummary(USER)).thenReturn(List.of());
        when(mapper.executionPauseReason(account(),72L)).thenReturn("RISK_PAUSED");
        var result=views.overview(account());
        var ordered=inOrder(mapper,sessions,calibration,projectionDomain);
        ordered.verify(mapper).lockPolicy();ordered.verify(mapper).lockUser(USER);
        ordered.verify(mapper).lockBinding(account());ordered.verify(sessions).adminSummary(USER);
        ordered.verify(calibration).phoneReplacementHardPause(USER,72L);
        ordered.verify(projectionDomain).eligibility(account(),null,null,false,List.of());
        assertThat(result.get("executionPauseReason")).isEqualTo("RISK_PAUSED");
        verify(mapper,never()).binding(any());verify(mapper,never()).ensureState(any());
        verifyNoInteractions(onboarding); // No current App must not be treated as executable.
    }
    @Test void grantHistoryAddsRestrictedMetadataAndAuditLinksWhileKeepingDomainTerminalStates() throws Exception {
        var expired=new GrantSummary("grant-expired","request-1",4,true,false,NOW-10000,NOW-1000,"EXPIRED",0);
        var consumed=new GrantSummary("grant-consumed","request-2",5,false,true,NOW-9000,NOW+1000,"CONSUMED",0);
        when(projectionDomain.grants(account(),20,20)).thenReturn(List.of(expired,consumed));
        when(mapper.grantMetadata(account(),20,20)).thenReturn(List.of(
                Map.of("grantId","grant-expired","targetInstallationId","installation-B","reason",REASON,
                        "operatorId",ADMIN,"evidenceRef","verification-safe-ref","issueOperationId","issue-1"),
                Map.of("grantId","grant-consumed","targetInstallationId","installation-C","reason",REASON,
                        "operatorId",ADMIN,"evidenceRef","verification-safe-ref-2","issueOperationId","issue-2",
                        "consumedOperationId","consume-2","consumedAt",NOW-500),
                Map.of("grantId","unrelated-grant","reason","unrelated private reason")));
        var issueAudit=Map.<String,Object>of("resourceId","grant-expired","operationId","issue-1",
                "commandType","PHONE_GRANT_ISSUE","status","SUCCEEDED","auditRef","audit-1");
        var consumeAudit=Map.<String,Object>of("resourceId","grant-consumed","operationId","issue-2",
                "commandType","PHONE_GRANT_ISSUE","status","SUCCEEDED","auditRef","audit-2");
        when(mapper.resourceOperations(account(),List.of("grant-expired","grant-consumed"))).thenReturn(List.of(issueAudit,consumeAudit));
        // Full evidence is available only in the separate raw record API, never this projection.
        when(mapper.grants(account(),20,20)).thenReturn(List.of(new Grant("grant-expired","request-1",USER,"SANDBOX","frozen-scope-run",
                "installation-B",4,true,false,NOW-10000,NOW-1000,"ACTIVE",REASON,"SECRET_INTERNAL_EVIDENCE_JSON",ADMIN,null,null,null)));
        var rows=views.grants(account(),20,20);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("status","EXPIRED").containsEntry("remainingUses",0)
                .containsEntry("reason",REASON).containsEntry("evidenceRef","verification-safe-ref")
                .containsEntry("operatorId",ADMIN).containsEntry("targetInstallationId","installation-B")
                .containsEntry("issueOperationId","issue-1").containsEntry("operations",List.of(issueAudit));
        assertThat(rows.get(1)).containsEntry("status","CONSUMED").containsEntry("consumedOperationId","consume-2")
                .containsEntry("consumedAt",NOW-500).containsEntry("operations",List.of(consumeAudit));
        String serialized=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows);
        assertThat(serialized).doesNotContain("evidenceJson","SECRET_INTERNAL_EVIDENCE_JSON","unrelated private reason",
                "requestHash","idempotencyKey","originalResultJson");
        verify(mapper,never()).grants(any(),anyInt(),anyInt());
        verify(projectionDomain).grants(account(),20,20);
    }
    @Test void requestHistoryUsesDomainStateAndAttachesOnlyThatRequestsAuditOperations() throws Exception {
        Request expired=new Request("request-expired","request-expired",USER,"SANDBOX","frozen-scope-run","installation-B",4,
                REASON,NOW-10000,NOW-1000,"EXPIRED",null,"support-owned","LINKED",null,null);
        Request closed=new Request("request-closed","request-closed",USER,"SANDBOX","frozen-scope-run","installation-C",4,
                REASON,NOW-9000,NOW+1000,"CLOSED",null,null,"UNLINKED","Verified close reason",NOW-500);
        when(projectionDomain.requests(account(),0,21)).thenReturn(List.of(expired,closed));
        var closeAudit=Map.<String,Object>of("resourceId","request-closed","operationId","close-original",
                "commandType","PHONE_REQUEST_CLOSE","status","SUCCEEDED","auditRef","audit-close");
        when(mapper.resourceOperations(account(),List.of("request-expired","request-closed"))).thenReturn(List.of(closeAudit));
        var rows=views.requestHistory(account(),0,21);
        assertThat(rows.get(0)).containsEntry("status","EXPIRED").containsEntry("operations",List.of());
        assertThat(rows.get(1)).containsEntry("status","CLOSED").containsEntry("closeReason","Verified close reason")
                .containsEntry("operations",List.of(closeAudit));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rows))
                .doesNotContain("evidenceJson","requestHash","idempotencyKey","originalResultJson");
        verify(projectionDomain).requests(account(),0,21);
        verify(mapper,never()).requests(any(),any(),anyInt(),anyInt());
        verify(mapper,never()).grantMetadata(any(),anyInt(),anyInt());
    }
    @Test void emptyHistoriesNeverQueryAnEmptyResourceIdSet(){
        when(projectionDomain.grants(account(),0,20)).thenReturn(List.of());
        when(projectionDomain.requests(account(),0,20)).thenReturn(List.of());
        assertThat(views.grants(account(),0,20)).isEmpty();
        assertThat(views.requestHistory(account(),0,20)).isEmpty();
        verify(mapper,never()).resourceOperations(any(),any());
    }
    @Test void metadataSqlProjectsEvidenceReferenceOnlyAndScopesBothHistoryQueries() throws Exception {
        String grantSql=String.join(" ",PhoneReplacementMapper.class.getMethod("grantMetadata",Account.class,int.class,int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertThat(grantSql).contains("JSON_UNQUOTE(JSON_EXTRACT(evidence_json,'$.reference')) evidenceRef")
                .doesNotContain("evidenceJson","SELECT *","status", "original_result_json");
        String auditSql=String.join(" ",PhoneReplacementMapper.class.getMethod("resourceOperations",Account.class,List.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertThat(auditSql).contains("audit_ref auditRef","resource_id IN")
                .doesNotContain("evidence_json","request_hash","idempotency_key","original_result_json","SELECT *");
        for(String sql:List.of(grantSql,auditSql))assertThat(sql)
                .contains("user_id=#{a.userId}","source_environment=#{a.sourceEnvironment}","run_id=#{a.runId}");
    }
    @Test void frozenScopeDoesNotLetNewIssueBypassTheRealDomainActiveGate(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var outbox=mock(EventOutboxService.class);
        var realDomain=new PhoneReplacementService(mapper,clock,audit,outbox);
        var operations=operations(new PhoneReplacementFacade(realDomain));
        when(mapper.lockUser(USER)).thenReturn("FROZEN");
        when(mapper.lockPolicy()).thenReturn(List.of());
        when(mapper.insertOperation(any(),any(),any(),any(),any(),any(),anyLong())).thenReturn(1);
        when(mapper.currentOperation(eq(account()),anyString())).thenAnswer(call->operation(call.getArgument(1),"PENDING"));
        when(evidence.find("evidence")).thenReturn(new PhoneReplacementVerificationMapper.Row("evidence",USER,"SANDBOX","frozen-scope-run",
                "request","installation-B",4,"support",ADMIN,NOW-1000,REASON,"verify-key","hash"));
        assertThatThrownBy(()->operations.issue(USER,new Issue("request",4L,true,true,REASON,"evidence",true),"new-key"))
                .hasMessage("USER_AUTH_REQUIRED");
        verify(mapper,times(2)).lockUser(USER); // existence lock succeeded; real new-issue gate then rejected FROZEN.
        verify(mapper,never()).insertGrant(any(),any(),any(),anyBoolean(),anyBoolean(),anyLong(),anyLong(),any(),any(),anyLong(),any());
        verifyNoInteractions(audit,outbox);
    }
}
