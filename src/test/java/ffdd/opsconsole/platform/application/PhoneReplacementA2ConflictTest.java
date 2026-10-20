package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.boundary.PhoneReplacementContract;
import ffdd.opsconsole.device.application.PhoneReplacementPolicyPublicationService;
import ffdd.opsconsole.device.mapper.PhoneReplacementPolicyOperationMapper;
import ffdd.opsconsole.platform.domain.*;
import ffdd.opsconsole.platform.dto.AuditCenterOverview.AuditOperationTicket;
import ffdd.opsconsole.platform.dto.*;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.platform.infrastructure.AuditOperationTicketEntity;
import ffdd.opsconsole.platform.mapper.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.*;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.PhoneReplacementAdminAccess;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Actual A2 approval wrapper, dispatcher, publisher conflict branch, and HTTP exception handler.
 * Storage/permissions are controlled inputs; database rollback is covered by the separate MySQL suite. */
class PhoneReplacementA2ConflictTest {
    @Test void approvalRetainsPhoneConflictDetailsThroughItsIdempotencyWrapper() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var domain=mock(PhoneReplacementContract.class);
        when(domain.lockCurrentPolicy()).thenReturn(new PhoneReplacementContract.Policy(true,true,1L,30,2,2L));
        var access=mock(PhoneReplacementAdminAccess.class);
        when(access.requirePolicyApproval()).thenReturn(7L);
        var config=mock(PlatformConfigFacade.class);
        var commands=mock(PhoneReplacementPolicyOperationMapper.class);
        var audit=mock(AuditLogService.class);var outbox=mock(EventOutboxService.class);
        var publisher=new PhoneReplacementPolicyPublicationService(domain,config,commands,access,audit,outbox,Clock.systemUTC());
        AuditReplayable target=new AuditReplayable(){
            public String domain(){return "E";}
            public ApiResult<?> replay(AuditReplayCommand command,AuditReplayContext context){
                assertThat(A2ReplayContext.isReplaying()).isTrue();
                return publisher.publish(Map.of(PhoneReplacementPolicyPublicationService.PREFIX+"allowReplacement","off"),
                        1L,"verified policy change",context.idempotencyKey(),true);
            }
        };
        var repository=mock(PlatformConfigRepository.class);
        when(repository.findActiveByKey(anyString())).thenReturn(Optional.empty());
        when(repository.findActiveByKey(A2RuntimePolicy.REASON_MIN_KEY)).thenReturn(Optional.of(
                new PlatformConfigItem(null,A2RuntimePolicy.REASON_MIN_KEY,"8 字","STRING","admin_a2","ADMIN",
                        "test baseline",1,null,null)));
        var tickets=mock(AuditOperationTicketMapper.class);var locks=mock(AuditObjectLockMapper.class);
        var guard=mock(AuditReplayBusinessPermissionGuard.class);
        when(guard.validateApproval(any())).thenReturn(ApiResult.ok());
        var policy=mock(A2AccessPolicy.class);when(policy.canAccessTicket(any())).thenReturn(true);
        var idempotency=mock(AdminIdempotencyService.class);
        when(idempotency.execute(anyString(),anyString(),anyString(),eq(AuditOperationTicket.class),any()))
                .thenAnswer(call->((Supplier<?>)call.getArgument(4)).get());
        var service=new OpsAuditCenterService(repository,audit,OpsReadTimeSeedPolicy.disabledForDirectConstruction(),
                tickets,mock(AuditOperationHistoryMapper.class),mock(AuditConfirmCategoryMapper.class),locks,guard,
                new AuditReplayDispatcher(List.of(target)),policy,json,idempotency);
        var ticket=new AuditOperationTicketEntity();ticket.setOperationId("PHONE-A2");ticket.setStatus("pending");
        ticket.setOperatorName("maker");ticket.setIsDeleted(0);
        ticket.setCommandJson(json.writeValueAsString(new AuditReplayCommand("E","e6_compute_config",Map.of())));
        when(tickets.selectActiveByOperationIdForUpdate("PHONE-A2")).thenReturn(ticket);
        var handler=new GlobalExceptionHandler(audit);
        for(String conflict:List.of("POLICY_VERSION_CHANGED","IDEMPOTENCY_MISMATCH")){
            if(conflict.equals("IDEMPOTENCY_MISMATCH"))
                when(commands.lockByKey(7L,PhoneReplacementPolicyPublicationService.SINGLE,"approval-key"))
                        .thenReturn(new PhoneReplacementPolicyOperationMapper.Row("PPO-old",7L,
                                PhoneReplacementPolicyPublicationService.SINGLE,"approval-key","different-hash",
                                "device_e6_flag_toggle","SUCCEEDED",1L,2L,"{}","audit-old"));
            assertThatThrownBy(()->service.approve("approval-key","PHONE-A2",
                    new AuditOperationDecisionRequest("verified policy approval","checker")))
                    .isInstanceOfSatisfying(PhoneReplacementException.class,error->{
                        var response=new MockHttpServletResponse();
                        var body=handler.handlePhoneReplacement(error,response);
                        assertThat(response.getStatus()).isEqualTo(409);
                        var wire=json.valueToTree(body);
                        assertThat(wire.path("data").path("conflictType").asText()).isEqualTo(conflict);
                        assertThat(wire.path("data").path("reason").asText()).isNotBlank().isNotEqualTo(conflict);
                        assertThat(wire.path("data").path("allowedActions").toString()).contains("CANCEL");
                    });
            assertThat(ticket.getStatus()).isEqualTo("pending");
            assertThat(A2ReplayContext.isReplaying()).isFalse();
        }
        verifyNoInteractions(config,audit,outbox,locks);
        verify(commands,never()).insert(any());verify(tickets,never()).updateById(any(AuditOperationTicketEntity.class));
    }
}
