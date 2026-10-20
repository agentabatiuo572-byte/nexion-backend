package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper.Row;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Actual publisher and current-DB authorization; only storage boundaries are mocked. */
class PhoneReplacementPolicyApprovalAccessTest {
    static final String APPROVE="platform_a2_operation_approve", READ="device_e6_read";
    static final String WRITE="device_e6_write", FLAG="device_e6_flag_toggle";
    final AdminMapper admins=mock(AdminMapper.class);
    final AdminRolePermissionMapper permissions=mock(AdminRolePermissionMapper.class);
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    final PhoneReplacementContract domain=mock(PhoneReplacementContract.class);
    final PlatformConfigFacade config=mock(PlatformConfigFacade.class);
    final PhoneReplacementPolicyOperationMapper commands=mock(PhoneReplacementPolicyOperationMapper.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final EventOutboxService events=mock(EventOutboxService.class);
    final PhoneReplacementAdminAccess access=new PhoneReplacementAdminAccess(new AdminPermissionCache(redis,permissions),admins);
    final PhoneReplacementPolicyPublicationService service=new PhoneReplacementPolicyPublicationService(domain,config,commands,access,audit,events,Clock.systemUTC());
    final AtomicReference<Row> stored=new AtomicReference<>();
    final PhoneReplacementContract.Policy policy=new PhoneReplacementContract.Policy(true,true,1L,30,2,2L);
    AdminEntity admin;

    @BeforeEach void setup(){
        admin=new AdminEntity();admin.setId(7L);admin.setStatus(1);admin.setIsDeleted(0);
        when(admins.selectById(7L)).thenReturn(admin);
        // JWT still advertises all rights: every denial must come from current database grants.
        var auth=new UsernamePasswordAuthenticationToken("7","unused",List.of(APPROVE,READ,WRITE,FLAG).stream().map(SimpleGrantedAuthority::new).toList());
        auth.setDetails(Map.of("subjectType","ADMIN"));SecurityContextHolder.getContext().setAuthentication(auth);
        rights(APPROVE,READ);
        when(domain.lockCurrentPolicy()).thenReturn(policy);when(domain.currentPolicy()).thenReturn(policy);
        when(domain.validatePolicy(any(),anyLong())).thenReturn(Map.of());
        when(commands.lockByKey(anyLong(),anyString(),anyString())).thenAnswer(call->{
            Row row=stored.get();return row!=null && row.actorId()==(long)call.getArgument(0)
                    && row.commandType().equals(call.getArgument(1)) && row.idempotencyKey().equals(call.getArgument(2))?row:null;
        });
        when(commands.insert(any())).thenAnswer(call->{stored.set(call.getArgument(0));return 1;});
        when(commands.complete(anyString(),anyLong(),anyString(),anyString())).thenAnswer(call->{
            Row row=stored.get();stored.set(new Row(row.operationId(),row.actorId(),row.commandType(),row.idempotencyKey(),
                    row.requestHash(),row.permissionCode(),"SUCCEEDED",row.submittedAt(),call.getArgument(1),call.getArgument(2),call.getArgument(3)));return 1;
        });
        when(commands.find(anyString())).thenAnswer(call->stored.get());
        when(commands.findByKey(anyLong(),anyString(),anyString())).thenAnswer(call->stored.get());
    }
    @AfterEach void cleanup(){A2ReplayContext.exitReplay();SecurityContextHolder.clearContext();}
    void rights(String... values){when(permissions.selectActivePermissionCodes(7L)).thenReturn(List.of(values));}
    Map<String,String> flag(){return Map.of(PhoneReplacementPolicyPublicationService.PREFIX+"allowReplacement","off");}
    void publish(){service.publish(flag(),2L,"verified phone policy approval","policy-key",true);}

    @Test void checkerWithOnlyApprovalAndExactReadCanPublishAndRecoverOriginalReceipt(){
        A2ReplayContext.enterReplay("A2-policy");publish();A2ReplayContext.exitReplay();
        assertThat(stored.get().status()).isEqualTo("SUCCEEDED");assertThat(stored.get().actorId()).isEqualTo(7L);
        var receipt=service.operation(stored.get().operationId());
        assertThat(receipt.originalResult()).isNotNull();assertThat(receipt.auditRef()).isNotBlank();
        assertThat(service.operationByKey(PhoneReplacementPolicyPublicationService.SINGLE,"policy-key").operationId()).isEqualTo(receipt.operationId());
        verify(commands,times(1)).insert(any());verify(audit,times(1)).recordRequired(any());verifyNoInteractions(redis);
    }
    @Test void checkerCanPublishCompleteBatchWithoutBecomingDirectWriter(){
        A2ReplayContext.enterReplay("A2-policy-batch");
        var result=service.publish(PhoneReplacementPolicyPublicationService.values(policy),2L,"verified whole phone policy","batch-key",false);
        assertThat(result.getCode()).isZero();assertThat(result.getData().receipt().commandType()).isEqualTo(PhoneReplacementPolicyPublicationService.BATCH);
        verify(commands,times(1)).insert(any());
    }
    @Test void replayRequiresBothCurrentDatabaseApprovalAndExactRead(){
        A2ReplayContext.enterReplay("A2-policy");
        for(String[] values:List.of(new String[]{APPROVE},new String[]{READ},new String[]{WRITE},new String[]{FLAG},new String[]{})){
            rights(values);
            assertThatThrownBy(this::publish).isInstanceOf(PhoneReplacementException.class).hasMessage("PERMISSION_DENIED");
        }
        verifyNoInteractions(domain,commands,config,audit,events,redis);
    }
    @Test void directCallsRemainDeniedForCheckerAndRequireA2ForWriter(){
        assertThatThrownBy(this::publish).hasMessage("PERMISSION_DENIED");
        rights(WRITE);
        assertThat(service.publish(flag(),2L,"verified phone policy approval","direct",true).getMessage()).isEqualTo("A2_CONFIRMATION_REQUIRED");
        verifyNoInteractions(domain,commands,config,audit,events);
    }
    @Test void receiptAccessUsesCurrentRightsAndDoesNotReexecute(){
        stored.set(new Row("PPO-prior",7L,PhoneReplacementPolicyPublicationService.SINGLE,"policy-key","hash",FLAG,
                "SUCCEEDED",1L,2L,"{\"policyVersion\":2}","audit-prior"));
        for(String[] values:List.of(new String[]{WRITE},new String[]{FLAG},new String[]{APPROVE,READ})){
            rights(values);assertThat(service.operation("PPO-prior").operationId()).isEqualTo("PPO-prior");
        }
        for(String[] values:List.of(new String[]{APPROVE},new String[]{READ},new String[]{})){
            rights(values);
            assertThatThrownBy(()->service.operation("PPO-prior")).hasMessage("PERMISSION_DENIED");
            assertThatThrownBy(()->service.operationByKey(PhoneReplacementPolicyPublicationService.SINGLE,"policy-key")).hasMessage("PERMISSION_DENIED");
        }
        verify(commands,never()).insert(any());verifyNoInteractions(config,audit,events,redis);
    }
    @Test void disabledDeletedAndNonAdminSubjectsCannotUseReplayOrRecovery(){
        A2ReplayContext.enterReplay("A2-policy");
        admin.setStatus(0);assertThatThrownBy(this::publish).hasMessage("ADMIN_AUTH_REQUIRED");
        admin.setStatus(1);admin.setIsDeleted(1);assertThatThrownBy(this::publish).hasMessage("ADMIN_AUTH_REQUIRED");
        admin.setIsDeleted(0);
        var user=new UsernamePasswordAuthenticationToken("7","unused",List.of(new SimpleGrantedAuthority(APPROVE),new SimpleGrantedAuthority(READ)));
        user.setDetails(Map.of("subjectType","USER"));SecurityContextHolder.getContext().setAuthentication(user);
        assertThatThrownBy(this::publish).hasMessage("ADMIN_AUTH_REQUIRED");
        assertThatThrownBy(()->service.operationByKey(PhoneReplacementPolicyPublicationService.SINGLE,"policy-key")).hasMessage("ADMIN_AUTH_REQUIRED");
        verifyNoInteractions(domain,config,audit,events);
    }
}
