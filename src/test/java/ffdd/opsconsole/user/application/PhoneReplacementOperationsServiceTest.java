package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.common.boundary.*;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.dto.PhoneReplacementCommands.*;
import ffdd.opsconsole.user.mapper.PhoneReplacementVerificationMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real access/cache classes; only database and cross-domain boundaries are mocked. */
class PhoneReplacementOperationsServiceTest {
    static final long USER=71, ACTOR=900, NOW=1_800_000_000_000L;
    static final String READ=PhoneReplacementOperationsService.READ;
    static final String GRANT=PhoneReplacementOperationsService.GRANT;
    static final String REVOKE=PhoneReplacementOperationsService.REVOKE;
    static final String REASON="Verified account support request";
    final Account account=new Account(USER,"SANDBOX","run-current");
    final AdminMapper admins=mock(AdminMapper.class);
    final AdminRolePermissionMapper permissionRows=mock(AdminRolePermissionMapper.class);
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final AtomicReference<List<String>> permissions=new AtomicReference<>(List.of(READ,GRANT,REVOKE));
    final AdminEntity admin=new AdminEntity();
    final OpsUserService users=mock(OpsUserService.class);
    final PhoneReplacementContract domain=mock(PhoneReplacementContract.class);
    final PhoneReplacementAdminReadContract views=mock(PhoneReplacementAdminReadContract.class);
    final PhoneReplacementPolicyOperations policies=mock(PhoneReplacementPolicyOperations.class);
    final PhoneReplacementSupportContract support=mock(PhoneReplacementSupportContract.class);
    final PhoneReplacementVerificationMapper verifications=mock(PhoneReplacementVerificationMapper.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final PhoneReplacementOperationsService service=new PhoneReplacementOperationsService(
            new PhoneReplacementAdminAccess(new AdminPermissionCache(redis,permissionRows),admins),users,domain,views,
            policies,support,verifications,audit,Clock.fixed(Instant.ofEpochMilli(NOW),ZoneOffset.UTC));

    @BeforeEach void setup() {
        admin.setId(ACTOR);admin.setStatus(1);admin.setIsDeleted(0);
        when(admins.selectById(ACTOR)).thenReturn(admin);
        when(permissionRows.selectActivePermissionCodes(ACTOR)).thenAnswer(call->permissions.get());
        when(users.profile(USER)).thenReturn(ApiResult.ok(mock(UserAccountView.class)));
        when(views.account(USER)).thenReturn(account);
        when(views.scope()).thenReturn(new PhoneReplacementAdminReadContract.Scope("SANDBOX","run-current"));
        authenticate("ADMIN");
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    void authenticate(String subject) {
        // Deliberately stale JWT authorities: service must trust current DB rights instead.
        var auth=new UsernamePasswordAuthenticationToken(Long.toString(ACTOR),"unused",
                List.of(READ,GRANT,REVOKE).stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(Map.of("subjectType",subject));SecurityContextHolder.getContext().setAuthentication(auth);
    }
    Issue issue(){return new Issue("request",4L,true,false,REASON,"evidence",true);}
    Confirm confirm(){return new Confirm(REASON,true);}
    PhoneReplacementVerificationMapper.Row evidence(){return evidence(USER,"SANDBOX","run-current","request",ACTOR);}
    static PhoneReplacementVerificationMapper.Row evidence(long user,String env,String run,String request,long actor){
        return new PhoneReplacementVerificationMapper.Row("evidence",user,env,run,request,"installation-B",4,"support-owned",actor,NOW-1000,REASON,"verify-key","hash");
    }
    Request request(String link){return new Request("request","request",USER,"SANDBOX","run-current","installation-B",4,
            REASON,NOW-1000,NOW+1000,"PENDING",null,"LINKED".equals(link)?"support-owned":null,link,null,null);}
    Operation operation(String type,String run){return new Operation("PRO-original",USER,"SANDBOX",run,"ADMIN",ACTOR,type,
            "original-key","original-hash","SUCCEEDED",NOW-2000,NOW-1900,"{}","GRANT","grant","audit-original");}
    Receipt receipt(String state){return new Receipt("PRO-original","PHONE_GRANT_ISSUE","SUCCEEDED",NOW-2000,NOW-1900,
            Map.of("grantId","grant","status","ACTIVE"),new GrantSummary("grant","request",4,true,false,NOW-2000,NOW-1000,state,0),"audit-original");}

    @Test void verificationRecoveryReturnsFrozenSnapshotWithoutRecheckingSupportOrWriting() throws Exception {
        long verifiedAt=NOW-3*86_400_000L;
        var prior=new PhoneReplacementVerificationMapper.Row("evidence-original",USER,"SANDBOX","run-current","request-original",
                "installation-original",4,"support-original",ACTOR,verifiedAt,"original private reason","original-key","original-hash");
        when(verifications.byKey(ACTOR,"original-key")).thenReturn(prior);
        var recovered=service.verificationByKey(USER,"original-key");
        assertThat(recovered).isEqualTo(new PhoneReplacementOperationsService.VerificationView(
                "evidence-original","request-original","support-original",ACTOR,verifiedAt));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(recovered))
                .doesNotContain("original private reason","original-hash","installation-original","idempotencyKey","requestHash");
        verify(views,never()).request(any(),any());
        verify(verifications).byKey(ACTOR,"original-key");verifyNoMoreInteractions(verifications);
        verifyNoInteractions(domain,support,audit);
    }
    @Test void verificationRecoveryDoesNotExposeMissingOrForeignAccountEnvironmentRunOrActor() {
        for(var row:Arrays.asList(null,evidence(USER+1,"SANDBOX","run-current","request",ACTOR),
                evidence(USER,"PRODUCTION","","request",ACTOR),evidence(USER,"SANDBOX","foreign-run","request",ACTOR),
                evidence(USER,"SANDBOX","run-current","request",ACTOR+1))) {
            when(verifications.byKey(ACTOR,"original-key")).thenReturn(row);
            assertThat(service.verificationByKey(USER,"original-key")).isNull();
        }
        verify(verifications,never()).insert(any());
        verify(views,never()).request(any(),any());verifyNoInteractions(domain,support,audit);
    }
    @Test void verificationRecoveryRechecksDatabasePermissionAndCustomerScopeBeforeLookup() {
        when(verifications.byKey(ACTOR,"original-key")).thenReturn(evidence());
        assertThat(service.verificationByKey(USER,"original-key")).isNotNull();
        clearInvocations(verifications);
        permissions.set(List.of(READ));
        assertThatThrownBy(()->service.verificationByKey(USER,"original-key")).hasMessage("PERMISSION_DENIED");
        permissions.set(List.of(GRANT));
        when(users.profile(USER)).thenReturn(ApiResult.fail(404,"SUPPORT_CUSTOMER_NOT_FOUND"));
        assertThatThrownBy(()->service.verificationByKey(USER,"original-key")).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
        verifyNoInteractions(verifications,domain,support,audit,redis);
    }
    @Test void readOnlySupportCannotMutateOrRecoverSensitiveCommandsDespiteStaleJwt() {
        permissions.set(List.of(READ));
        when(views.overview(account)).thenReturn(Map.of("safe",true));
        assertThat(service.binding(USER)).containsEntry("safe",true);
        for(Runnable action:List.<Runnable>of(
                ()->service.verify(USER,"request",new Verify(REASON,true),"key"),
                ()->service.issue(USER,issue(),"key"),()->service.close(USER,"request",confirm(),"key"),
                ()->service.revoke(USER,"grant",confirm(),"key"),()->service.operation("PRO-original"),
                ()->service.operationByKey("PHONE_GRANT_ISSUE","key")))
            assertThatThrownBy(action::run).hasMessage("PERMISSION_DENIED");
        verifyNoInteractions(domain,verifications,support,audit,redis);
    }
    @Test void grantAndRevokePermissionsRemainIndependent() {
        permissions.set(List.of(GRANT));
        service.close(USER,"request",confirm(),"close-key");
        verify(domain).closeRequest(account,ACTOR,"request",REASON,"close-key");
        assertThatThrownBy(()->service.revoke(USER,"grant",confirm(),"key")).hasMessage("PERMISSION_DENIED");
        permissions.set(List.of(REVOKE));
        service.revoke(USER,"grant",confirm(),"revoke-key");
        verify(domain).revokeGrant(account,ACTOR,"grant",REASON,"revoke-key");
        assertThatThrownBy(()->service.close(USER,"request",confirm(),"key")).hasMessage("PERMISSION_DENIED");
    }
    @Test void revokedDatabasePermissionBlocksOriginalRecoveryWithUnchangedAuthentication() {
        when(domain.adminOperationContext("PRO-original")).thenReturn(operation("PHONE_GRANT_ISSUE","run-current"));
        when(domain.operation(account,"PRO-original")).thenReturn(receipt("EXPIRED"));
        assertThat(service.operation("PRO-original").status()).isEqualTo("SUCCEEDED");
        clearInvocations(domain);
        permissions.set(List.of(READ));
        assertThatThrownBy(()->service.operation("PRO-original")).hasMessage("PERMISSION_DENIED");
        assertThatThrownBy(()->service.operationByKey("PHONE_GRANT_ISSUE","original-key")).hasMessage("PERMISSION_DENIED");
        verifyNoInteractions(domain);
        verifyNoInteractions(redis);
    }
    @Test void disabledDeletedAndNonAdminSubjectsCannotUseFinePermissions() {
        admin.setStatus(0);
        assertThatThrownBy(()->service.issue(USER,issue(),"key")).hasMessage("ADMIN_AUTH_REQUIRED");
        admin.setStatus(1);admin.setIsDeleted(1);
        assertThatThrownBy(()->service.binding(USER)).hasMessage("ADMIN_AUTH_REQUIRED");
        admin.setIsDeleted(0);authenticate("USER");
        assertThatThrownBy(()->service.operation("PRO-original")).hasMessage("ADMIN_AUTH_REQUIRED");
        verifyNoInteractions(domain,verifications,views,users);
    }
    @Test void databasePermissionFailureDoesNotFallBackToJwtRights() {
        when(permissionRows.selectActivePermissionCodes(ACTOR)).thenThrow(new IllegalStateException("permission db unavailable"));
        assertThatThrownBy(()->service.revoke(USER,"grant",confirm(),"key")).hasMessage("permission db unavailable");
        verifyNoInteractions(domain,users,redis);
    }
    @Test void customerScopeDenialBlocksReadsWritesAndOriginalReceiptRecovery() {
        when(users.profile(USER)).thenReturn(ApiResult.fail(404,"SUPPORT_CUSTOMER_NOT_FOUND"));
        when(domain.adminOperationContext("PRO-original")).thenReturn(operation("PHONE_GRANT_ISSUE","run-current"));
        assertThatThrownBy(()->service.binding(USER)).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
        assertThatThrownBy(()->service.issue(USER,issue(),"key")).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
        assertThatThrownBy(()->service.operation("PRO-original")).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
        verifyNoInteractions(views,verifications);
        verify(domain,never()).operation(any(),any());
        verify(domain,never()).issueGrant(any(),anyLong(),any(),any(),any());
    }
    static Stream<PhoneReplacementVerificationMapper.Row> wrongEvidence(){return Stream.of(
            null,evidence(USER+1,"SANDBOX","run-current","request",ACTOR),
            evidence(USER,"PRODUCTION","","request",ACTOR),evidence(USER,"SANDBOX","other-run","request",ACTOR),
            evidence(USER,"SANDBOX","run-current","other-request",ACTOR),evidence(USER,"SANDBOX","run-current","request",ACTOR+1));}
    @ParameterizedTest @MethodSource("wrongEvidence")
    void missingOrForeignEvidenceCannotReachGrantDomain(PhoneReplacementVerificationMapper.Row row) {
        when(verifications.find("evidence")).thenReturn(row);
        assertThatThrownBy(()->service.issue(USER,issue(),"key")).hasMessage("PHONE_REPLACEMENT_EVIDENCE_REQUIRED");
        verifyNoInteractions(domain,audit);
    }
    @Test void verificationRequiresExplicitConfirmationLinkedRecordAndCurrentOwnership() {
        assertThatThrownBy(()->service.verify(USER,"request",new Verify(REASON,false),"key"))
                .hasMessage("PHONE_REPLACEMENT_CONFIRMATION_REQUIRED");
        when(views.request(account,"request")).thenReturn(request("UNLINKED"));
        assertThatThrownBy(()->service.verify(USER,"request",new Verify(REASON,true),"key"))
                .hasMessage("PHONE_REPLACEMENT_EVIDENCE_REQUIRED");
        when(views.request(account,"request")).thenReturn(request("LINKED"));
        doThrow(new BizException(403,"PERMISSION_DENIED")).when(support).requireConversationOwner(USER,"support-owned");
        assertThatThrownBy(()->service.verify(USER,"request",new Verify(REASON,true),"key")).hasMessage("PERMISSION_DENIED");
        verify(verifications,never()).insert(any());verifyNoInteractions(audit,domain);
    }
    @Test void verifiedSnapshotReplaysWithoutCreatingAnotherAttestationAndRejectsChangedInput() {
        var saved=new AtomicReference<PhoneReplacementVerificationMapper.Row>();
        when(views.request(account,"request")).thenReturn(request("LINKED"));
        when(verifications.byKey(ACTOR,"verify-key")).thenAnswer(call->saved.get());
        when(verifications.insert(any())).thenAnswer(call->{saved.set(call.getArgument(0));return 1;});
        var first=service.verify(USER,"request",new Verify(REASON,true),"verify-key");
        assertThat(service.verify(USER,"request",new Verify(REASON,true),"verify-key")).isEqualTo(first);
        assertThat(saved.get().userId()).isEqualTo(USER);assertThat(saved.get().runId()).isEqualTo("run-current");
        assertThat(saved.get().targetInstallationId()).isEqualTo("installation-B");
        assertThatThrownBy(()->service.verify(USER,"request",new Verify("A changed confirmation reason",true),"verify-key"))
                .hasMessage("IDEMPOTENCY_MISMATCH");
        verify(verifications,times(1)).insert(any());verify(audit,times(1)).recordRequired(any());
        verify(support,times(1)).requireConversationOwner(USER,"support-owned");
        permissions.set(List.of(READ));
        assertThatThrownBy(()->service.verify(USER,"request",new Verify(REASON,true),"verify-key")).hasMessage("PERMISSION_DENIED");
    }
    @Test void expiredOriginalGrantReceiptPassesThroughWithoutRecheckingCurrentRequestOrRenewingEvidence() {
        when(verifications.find("evidence")).thenReturn(evidence());
        Receipt original=receipt("EXPIRED");
        when(domain.issueGrant(eq(account),eq(ACTOR),any(),any(),eq("original-key"))).thenReturn(original);
        assertThat(service.issue(USER,issue(),"original-key")).isSameAs(original);
        var capture=org.mockito.ArgumentCaptor.forClass(VerifiedEvidence.class);
        verify(domain).issueGrant(eq(account),eq(ACTOR),eq(new IssueInput("request",4,true,false,REASON)),capture.capture(),eq("original-key"));
        assertThat(capture.getValue().reference()).isEqualTo("evidence");
        assertThat(capture.getValue().snapshot()).containsEntry("verifiedAt",NOW-1000);
        verify(views,never()).request(any(),any());verify(verifications,never()).insert(any());verifyNoInteractions(support,audit);
    }
    @Test void recoveryUsesTrustedScopeBeforeLookupAndLeavesMissingResultUnknown() {
        var op=operation("PHONE_GRANT_ISSUE","run-current");
        when(domain.adminOperationContextByKey(ACTOR,"PHONE_GRANT_ISSUE","original-key","SANDBOX","run-current")).thenReturn(op);
        when(domain.operation(account,"PRO-original")).thenReturn(receipt("REVOKED"));
        assertThat(((GrantSummary)service.operationByKey("PHONE_GRANT_ISSUE","original-key").currentResource()).status()).isEqualTo("REVOKED");
        var order=inOrder(views,domain);
        order.verify(views).scope();
        order.verify(domain).adminOperationContextByKey(ACTOR,"PHONE_GRANT_ISSUE","original-key","SANDBOX","run-current");
        assertThat(service.operationByKey("PHONE_GRANT_ISSUE","not-found")).isNull();
        verify(domain,never()).issueGrant(any(),anyLong(),any(),any(),any());
    }
    @Test void byIdRejectsForeignRunAndWrongActionPermissionBeforeReadingReceipt() {
        when(domain.adminOperationContext("PRO-original")).thenReturn(operation("PHONE_GRANT_ISSUE","other-run"));
        assertThatThrownBy(()->service.operation("PRO-original")).hasMessage("PHONE_OPERATION_UNAVAILABLE");
        permissions.set(List.of(REVOKE));
        assertThatThrownBy(()->service.operation("PRO-original")).hasMessage("PERMISSION_DENIED");
        verify(domain,never()).operation(any(),any());
    }
    @Test void hundredRowPageUsesOneExtraRowAndReturnsOnlyRequestedPage() {
        var rows=new ArrayList<Request>();for(int i=0;i<101;i++)rows.add(request("LINKED"));
        when(domain.requests(account,100,101)).thenReturn(rows);
        var result=service.requests(USER,2,100);
        assertThat(result.items()).hasSize(100);assertThat(result.hasMore()).isTrue();
        assertThatThrownBy(()->service.requests(USER,1,101)).hasMessage("PAGE_INVALID");
    }
}
