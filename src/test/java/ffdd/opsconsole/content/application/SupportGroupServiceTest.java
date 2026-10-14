package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.dto.SupportGroupRequests.*;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionTemplate;

@org.junit.jupiter.api.extension.ExtendWith(org.mockito.junit.jupiter.MockitoExtension.class)
class SupportGroupServiceTest {
    private final SupportGroupMapper mapper=mock(SupportGroupMapper.class);
    private final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    private final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final org.springframework.context.ApplicationEventPublisher events=mock(org.springframework.context.ApplicationEventPublisher.class);
    private final ffdd.opsconsole.content.mapper.SupportAgentMapper profiles=mock(ffdd.opsconsole.content.mapper.SupportAgentMapper.class);
    private final SupportGroupService service=new SupportGroupService(mapper,ownership,idempotency,audit,events,profiles);
    private final LocalDateTime at=LocalDateTime.of(2026,10,7,0,0);
    @BeforeEach void setup(){
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","",List.of(
            new SimpleGrantedAuthority("service_m1_write"),new SimpleGrantedAuthority("service_m1_read"),
            new SimpleGrantedAuthority("platform_a1_write"),new SimpleGrantedAuthority("platform_a1_read"))));
        when(ownership.actorId()).thenReturn(1L);when(ownership.currentSuperAdmin()).thenReturn(true);when(mapper.now()).thenReturn(at);
        when(mapper.lockAccount(anyLong())).thenAnswer(i->new Account(i.getArgument(0),1,0L));
        when(idempotency.execute(anyString(),anyString(),anyString(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(4)).get());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    private void supervisor(){when(ownership.currentSuperAdmin()).thenReturn(false);doThrow(new BizException(403,"SUPER_REQUIRED")).when(ownership).requireSuperAdminSnapshot();doThrow(new BizException(403,"SUPER_REQUIRED")).when(ownership).requireSuperAdmin();when(mapper.qualificationCurrent(1L,"SUPERVISOR")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(1L,1L,"SUPERVISOR","ENABLED",1L,at));}
    @Test void newServiceAccountCreatesOnlyPersonalQualificationAndUngroupedEnabledProfile() throws Exception {
        when(mapper.compatibleAccount(3L)).thenReturn(1);
        when(mapper.insertQualification(3L,"SERVICE","ENABLED",at,1L,1L,"new service account","1:create-key")).thenReturn(1);
        when(mapper.insertMember(3L,null,at,1L,1L,"new service account","1:create-key")).thenReturn(1);
        when(profiles.ensureDefaultProfile(3L,"GENERAL","通用客服","support","账户,提现,设备",12,at)).thenReturn(1);
        service.accountCreatedService(3L,"create-key","new service account");
        verify(mapper).insertQualification(3L,"SERVICE","ENABLED",at,1L,1L,"new service account","1:create-key");
        verify(mapper,never()).insertQualification(anyLong(),eq("SUPERVISOR"),anyString(),any(),anyLong(),anyLong(),anyString(),anyString());
        verify(mapper).insertMember(3L,null,at,1L,1L,"new service account","1:create-key");
        verify(profiles).ensureDefaultProfile(3L,"GENERAL","通用客服","support","账户,提现,设备",12,at);
        verify(audit).recordRequired(argThat(r->"SUPPORT_GROUP_ACCOUNT_QUALIFICATIONS_CREATED".equals(r.getAction())&&Long.valueOf(1L).equals(r.getActorId())));
        var transaction=SupportGroupService.class.getMethod("accountCreatedService",Long.class,String.class,String.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.MANDATORY);
    }
    @Test void newServiceQualificationWriteFailureStopsFurtherInitialization() {
        when(mapper.compatibleAccount(3L)).thenReturn(1);
        assertThatThrownBy(()->service.accountCreatedService(3L,"create-key","new service account"))
                .hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");
        verify(mapper,never()).insertMember(anyLong(),any(),any(),anyLong(),anyLong(),anyString(),anyString());
        verifyNoInteractions(profiles,audit);
    }
    @Test void newServiceProfileWriteFailureCannotReturnSuccess() {
        when(mapper.compatibleAccount(3L)).thenReturn(1);
        when(mapper.insertQualification(anyLong(),eq("SERVICE"),eq("ENABLED"),eq(at),eq(1L),eq(1L),anyString(),anyString())).thenReturn(1);
        when(mapper.insertMember(anyLong(),isNull(),eq(at),eq(1L),eq(1L),anyString(),anyString())).thenReturn(1);
        assertThatThrownBy(()->service.accountCreatedService(3L,"create-key","new service account"))
                .hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");
        verifyNoInteractions(audit);
    }
    @Test void creationProxyRejectsMandatoryCallWithoutAnOuterTransaction() {
        var transactions=new TrackingTransactions();
        assertThatThrownBy(()->creationProxy(transactions).accountCreatedService(3L,"create-key","new service account"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(transactions.begins).isZero();assertThat(transactions.commits).isZero();assertThat(transactions.rollbacks).isZero();
        verifyNoInteractions(mapper,profiles,audit);
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void creationProxyParticipatesInOuterCommitOrRollback(int profileWriteRows) {
        when(mapper.compatibleAccount(3L)).thenReturn(1);
        when(mapper.insertQualification(3L,"SERVICE","ENABLED",at,1L,1L,"new service account","1:create-key")).thenReturn(1);
        when(mapper.insertMember(3L,null,at,1L,1L,"new service account","1:create-key")).thenReturn(1);
        when(profiles.ensureDefaultProfile(3L,"GENERAL","通用客服","support","账户,提现,设备",12,at)).thenReturn(profileWriteRows);
        var transactions=new TrackingTransactions();var proxy=creationProxy(transactions);
        Runnable create=()->new TransactionTemplate(transactions).executeWithoutResult(status->
                proxy.accountCreatedService(3L,"create-key","new service account"));
        if(profileWriteRows==0) {
            assertThatThrownBy(create::run).isInstanceOf(BizException.class).hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");
            assertThat(transactions.commits).isZero();assertThat(transactions.rollbacks).isEqualTo(1);
            verifyNoInteractions(audit);
        } else {
            create.run();assertThat(transactions.commits).isEqualTo(1);assertThat(transactions.rollbacks).isZero();
            verify(audit).recordRequired(any());
        }
        assertThat(transactions.begins).isEqualTo(1);
        verify(mapper).insertQualification(3L,"SERVICE","ENABLED",at,1L,1L,"new service account","1:create-key");
        verify(mapper).insertMember(3L,null,at,1L,1L,"new service account","1:create-key");
        verify(profiles).ensureDefaultProfile(3L,"GENERAL","通用客服","support","账户,提现,设备",12,at);
    }

    private SupportGroupService creationProxy(TrackingTransactions transactions) {
        var factory=new ProxyFactory(service);factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return (SupportGroupService)factory.getProxy();
    }

    /** Tracks real Spring proxy transaction decisions only; this does not prove MySQL data rollback. */
    private static final class TrackingTransactions extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Transaction> current=new ThreadLocal<>();
        int begins,commits,rollbacks;
        private static final class Transaction implements SmartTransactionObject {
            boolean active,rollbackOnly;
            @Override public boolean isRollbackOnly(){return rollbackOnly;}
            @Override public void flush(){ }
        }
        @Override protected Object doGetTransaction(){var transaction=current.get();return transaction==null?new Transaction():transaction;}
        @Override protected boolean isExistingTransaction(Object transaction){return ((Transaction)transaction).active;}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){
            ((Transaction)transaction).active=true;current.set((Transaction)transaction);begins++;
        }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status){((Transaction)status.getTransaction()).rollbackOnly=true;}
        @Override protected void doCommit(DefaultTransactionStatus status){commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status){rollbacks++;}
        @Override protected void doCleanupAfterCompletion(Object transaction){current.remove();}
    }
    @Test void supervisorCreatesOwnGroupWithoutThrowingPermissionProbe(){
        supervisor();var group=new Group(8L,"Own",1L,"ENABLED",1L);
        when(mapper.insertGroup(anyMap())).thenAnswer(i->{i.<Map<String,Object>>getArgument(0).put("id",8L);return 1;});
        when(mapper.insertOwner(eq(8L),eq(1L),eq(at),eq(1L),eq(1L),anyString(),anyString())).thenReturn(1);
        when(mapper.group(8L)).thenReturn(group);
        assertThat(service.create("key",new Create("Own",1L,"合法操作原因说明")).getData()).isEqualTo(group);
        verify(ownership,times(4)).currentSuperAdmin();verify(ownership,never()).requireSuperAdmin();
        verify(audit).recordRequired(any());
    }
    @Test void superRoleRevocationIsRecheckedAfterAccountLocksBeforeCreate(){
        supervisor();when(ownership.currentSuperAdmin()).thenReturn(true,true,false);
        assertThatThrownBy(()->service.create("key",new Create("Other",2L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_FORBIDDEN");
        verify(mapper).lockAccount(1L);verify(mapper).lockAccount(2L);
        verify(mapper,never()).insertGroup(anyMap());verifyNoInteractions(audit);
    }
    @Test void managerWithoutGroupsGetsEmptyOwnedScope(){supervisor();var scope=new ReadScope(1L,ReadMode.MANAGED,null,null);when(ownership.defaultQueryScope(null,null)).thenReturn(scope);assertThat(service.groups()).isEmpty();verify(mapper).scopedGroups(scope);verify(mapper,never()).groups(isNull());}
    @Test void cannotForgeOwner(){supervisor();assertThatThrownBy(()->service.create("key",new Create("组",2L,"合法操作原因说明"))).isInstanceOf(BizException.class);verifyNoInteractions(idempotency);}
    @Test void cannotRenameOtherOwnerGroup(){supervisor();when(mapper.lockGroup(8L)).thenReturn(new Group(8L,"Other",2L,"ENABLED",1L));assertThatThrownBy(()->service.rename(8L,"key",new Rename("new",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_NOT_FOUND");verify(mapper,never()).rename(any(),any(),any(),any());}
    @Test void managerCannotTakeUngroupedMember(){supervisor();when(mapper.memberCurrent(3L)).thenReturn(new Member(1L,3L,null,1L,at));assertThatThrownBy(()->service.move(3L,"key",new Move(8L,1L,null,1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_FORBIDDEN");verify(mapper,never()).closeMember(any(),any(),any());}
    @Test void staleVersionCannotMutate(){Group g=new Group(8L,"Own",1L,"ENABLED",2L);when(mapper.group(8L)).thenReturn(g);when(mapper.lockGroup(8L)).thenReturn(g);assertThatThrownBy(()->service.rename(8L,"key",new Rename("new",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");verifyNoInteractions(audit);}
    @Test void nonemptyGroupCannotDisable(){Group g=new Group(8L,"Own",1L,"ENABLED",1L);when(mapper.group(8L)).thenReturn(g);when(mapper.lockGroup(8L)).thenReturn(g);when(mapper.memberCount(8L)).thenReturn(1L);assertThatThrownBy(()->service.status(8L,"key",new Status("DISABLED",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_HANDOVER_REQUIRED");}
    @Test void activeRouteAlsoBlocksExit(){Group g=new Group(8L,"Own",1L,"DISABLED",1L);when(mapper.group(8L)).thenReturn(g);when(mapper.lockGroup(8L)).thenReturn(g);when(mapper.routeCount(8L)).thenReturn(1L);assertThatThrownBy(()->service.status(8L,"key",new Status("ARCHIVED",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_HANDOVER_REQUIRED");}
    @Test void unfinishedPreviewOrOperationBlocksExitEvenWithNoMembersAndRoutes(){Group g=new Group(8L,"Own",1L,"DISABLED",1L);when(mapper.group(8L)).thenReturn(g);when(mapper.lockGroup(8L)).thenReturn(g);when(mapper.pendingGroupOperations(8L)).thenReturn(1L);assertThatThrownBy(()->service.status(8L,"key",new Status("ARCHIVED",1L,"合法操作原因说明"))).hasMessage("SUPPORT_GROUP_HANDOVER_REQUIRED");verify(mapper,never()).status(any(),any(),any(),any());verifyNoInteractions(events);}
    @Test void groupRouteIsSuperOnlyAndCannotBorrowSupervisorPersonalRead(){supervisor();assertThatThrownBy(()->service.route(20L,"route-key",new ffdd.opsconsole.content.dto.SupportGroupRequests.Route(8L,0L,null,1L,"合法操作原因说明"))).hasMessage("SUPER_REQUIRED");verifyNoInteractions(idempotency);verify(mapper,never()).insertRoute(any(),any(),any(),any(),any(),any(),any());}
    @Test void ordinaryAccountDisableEmitsActorInvalidationWithoutMovingAssets(){service.accountChanged(3L,null,true,"disable","合法操作原因说明");verify(events).publishEvent(new ScopeChanged(Set.of(3L),"合法操作原因说明"));verify(mapper,never()).closeMember(any(),any(),any());}
    @Test void routingVersionsCannotTruncateFractionalJsonButFirstRouteAllowsZero() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var request=json.readValue("{\"targetGroupId\":8,\"expectedRouteVersion\":0,\"targetGroupVersion\":1,\"reason\":\"explicit owned route proof\"}",ffdd.opsconsole.content.dto.SupportGroupRequests.Route.class);
        assertThat(request.expectedRouteVersion()).isZero();
        assertThatThrownBy(()->json.readValue("{\"targetGroupId\":8,\"expectedRouteVersion\":1.5}",ffdd.opsconsole.content.dto.SupportGroupRequests.Route.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @Test void ordinaryDisablePreservesMemberBindingAndQualification(){when(mapper.boundCount(3L)).thenReturn(10L);service.accountChanging(3L,null,true,"disable","合法操作原因说明");service.accountChanged(3L,null,true,"disable","合法操作原因说明");verify(mapper,never()).closeMember(any(),any(),any());verify(mapper,never()).closeQualification(any(),any(),any());verifyNoInteractions(audit);}
    @Test void archivedOwnedGroupBlocksOldAccountDisable(){when(mapper.qualification(3L,"SUPERVISOR")).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(1L,3L,"SUPERVISOR","ENABLED",1L,at));when(mapper.ownedGroupCount(3L)).thenReturn(1L);assertThatThrownBy(()->service.accountChanging(3L,null,true,"disable","合法操作原因说明")).hasMessage("SUPPORT_GROUP_OWNER_HANDOVER_REQUIRED");verify(mapper,never()).closeQualification(any(),any(),any());}
    @Test void roleRemovalRequiresPersonalHandover(){when(mapper.boundCount(3L)).thenReturn(2L);assertThatThrownBy(()->service.accountChanging(3L,"finance",false,"role","合法操作原因说明")).hasMessage("SUPPORT_PERSONAL_HANDOVER_REQUIRED");}
    @Test void peopleAreUnionNotRoleSum(){when(mapper.supervisors()).thenReturn(List.of(Map.of("adminId",1L),Map.of("adminId",2L)));when(mapper.memberIds()).thenReturn(List.of(2L,3L));assertThat(service.supervisors()).containsEntry("supervisorCount",2).containsEntry("memberCount",2).containsEntry("peopleCount",3);}
    @Test void legacyGeneralAliasRetainsExplicitServiceQualificationAndCannotGrantIt() {
        when(mapper.cutoverApplied()).thenReturn(1);
        when(mapper.qualified(3L,"SERVICE")).thenReturn(1);
        service.validateLegacySeat(3L,"GENERAL");
        when(mapper.qualified(3L,"SERVICE")).thenReturn(0);
        assertThatThrownBy(()->service.validateLegacySeat(3L,"GENERAL"))
            .hasMessage("SUPPORT_EXPLICIT_QUALIFICATION_REQUIRED");
        verifyNoInteractions(idempotency,audit,events);
    }
    @Test void legacySeatCannotGrantQualificationAfterCutover(){when(mapper.cutoverApplied()).thenReturn(1);assertThatThrownBy(()->service.validateLegacySeat(3L,"MANAGER")).hasMessage("SUPPORT_EXPLICIT_QUALIFICATION_REQUIRED");}
    @Test void changedQualificationFailureIsNotSwallowed(){var q=new ffdd.opsconsole.content.domain.SupportGroupFacts.Qualification(1L,3L,"SUPERVISOR","ENABLED",1L,at);when(mapper.qualification(3L,"SUPERVISOR")).thenReturn(q);when(mapper.qualifications(3L)).thenReturn(List.of(q));when(mapper.closeQualification(1L,1L,at)).thenReturn(0);assertThatThrownBy(()->service.accountChanged(3L,null,true,"disable","合法操作原因说明")).hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");verifyNoInteractions(audit);}
    @Test void everyGroupVersionIncrementRejectsUnrepresentableNextVersionBeforeAnyMutation(){
        long max=9007199254740991L;var group=new Group(8L,"组一",1L,"ENABLED",max);
        when(mapper.group(8L)).thenReturn(group);when(mapper.lockGroup(8L)).thenReturn(group);
        when(mapper.memberCurrent(3L)).thenReturn(new Member(7L,3L,8L,1L,at));
        List<Runnable> writes=List.of(
            ()->service.rename(8L,"rename-max",new Rename("新组名",max,"合法操作原因说明")),
            ()->service.status(8L,"status-max",new Status("DISABLED",max,"合法操作原因说明")),
            ()->service.owner(8L,"owner-max",new ffdd.opsconsole.content.dto.SupportGroupRequests.Owner(2L,max,"合法操作原因说明")),
            ()->service.move(3L,"move-max",new Move(null,1L,max,null,"合法操作原因说明")),
            ()->service.route(20L,"route-max",new ffdd.opsconsole.content.dto.SupportGroupRequests.Route(8L,0L,null,max,"合法操作原因说明")));
        for(Runnable write:writes)assertThatThrownBy(write::run).isInstanceOf(BizException.class).hasMessage("SUPPORT_GROUP_VERSION_CONFLICT");
        verify(mapper,never()).rename(anyLong(),anyString(),anyLong(),any());
        verify(mapper,never()).status(anyLong(),anyString(),anyLong(),any());
        verify(mapper,never()).owner(anyLong(),anyLong(),anyLong(),any());
        verify(mapper,never()).closeOwner(anyLong(),anyLong(),any());
        verify(mapper,never()).touch(anyLong(),anyLong(),any());
        verify(mapper,never()).closeMember(anyLong(),anyLong(),any());
        verify(mapper,never()).insertMember(anyLong(),any(),any(),anyLong(),anyLong(),anyString(),anyString());
        verify(mapper,never()).closeRoute(anyLong(),anyLong(),any());
        verify(mapper,never()).insertRoute(anyLong(),any(),any(),anyLong(),anyLong(),anyString(),anyString());
        verifyNoInteractions(audit,events);
    }
}
