package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.SupportRules;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.dto.SupportRulesRequest;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;

class SupportRulesUpgradeTest {
    final SupportBindingMapper mapper=mock(SupportBindingMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final SupportBindingService service=new SupportBindingService(mapper,ownership,idempotency,audit,
            mock(ApplicationEventPublisher.class),mock(SupportAgentRepository.class),mock(ProductionSupportPathGuard.class),
            mock(SupportTicketOwnerService.class),mock(SupportGroupMapper.class));
    @BeforeEach void setup(){
        when(ownership.actorId()).thenReturn(7L);
        when(mapper.rulesWriteGrantSnapshot(7L)).thenReturn(java.util.List.of(11L));
        when(mapper.rulesWriteGrant(7L)).thenReturn(java.util.List.of(11L));
        doAnswer(call->((Supplier<?>)call.getArgument(4)).get()).when(idempotency)
                .executeRetained(anyString(),anyString(),anyString(),eq(ApiResult.class),any());
    }
    SupportRulesRequest request(String mode,Integer depth,String reason){return new SupportRulesRequest(30,15,7,mode,depth,9L,reason,"SUPERVISOR");}
    void writable(){
        when(mapper.rules()).thenReturn(new SupportRules(9L,30,15,7,"UNCONFIGURED",null,"SUPERVISOR",null),
                new SupportRules(10L,30,15,7,"UNLIMITED",null,"SUPERVISOR",null));
        when(mapper.updateRules(30,15,7,"UNLIMITED",null,9L,7L,"Controlled install upgrade","SUPERVISOR")).thenReturn(1);
    }
    @Test void sentinelCannotBeWrittenThroughManagementApiAndRulesStillRequireEightCharacters(){
        assertThatThrownBy(()->service.updateRules("original-key",request("UNCONFIGURED",null,"Controlled install upgrade"))).isInstanceOf(BizException.class).hasMessage("SUPPORT_RULES_INVALID");
        assertThatThrownBy(()->service.updateRules("original-key",request("UNLIMITED",null,"六字有效理由"))).isInstanceOf(BizException.class);
        verifyNoInteractions(idempotency,audit);
        verify(mapper,times(2)).rulesWriteGrantSnapshot(7L);verifyNoMoreInteractions(mapper);
    }
    @Test @SuppressWarnings("unchecked") void auditedCasUpgradeOnlyMutatesRulesAndPreservesOriginalActorScopedKey(){
        writable();
        assertThat(service.updateRules("original-key",request("UNLIMITED",null,"Controlled install upgrade")).getData().version()).isEqualTo(10L);
        var entry=org.mockito.ArgumentCaptor.forClass(AuditLogWriteRequest.class);verify(audit).recordRequired(entry.capture());
        assertThat((java.util.Map<String,Object>)entry.getValue().getDetail()).containsKeys("before","after","request","reason","commandKey");
        verify(idempotency).executeRetained(eq("SUPPORT_RULES:7"),eq("original-key"),anyString(),eq(ApiResult.class),any());
        verify(mapper).lockRules();verify(mapper,times(2)).rules();
        verify(mapper).rulesWriteGrantSnapshot(7L);verify(mapper).rulesWriteGrant(7L);
        verify(mapper).updateRules(30,15,7,"UNLIMITED",null,9L,7L,"Controlled install upgrade","SUPERVISOR");
        verifyNoMoreInteractions(mapper);
    }
    @Test void staleVersionAndAuditFailureCannotBeReportedAsSuccessfulUpgrade(){
        when(mapper.rules()).thenReturn(new SupportRules(10L,30,15,7,"LIMITED",0,"SUPERVISOR",null));
        assertThatThrownBy(()->service.updateRules("original-key",request("UNLIMITED",null,"Controlled install upgrade")))
                .isInstanceOf(BizException.class).hasMessage("SUPPORT_RULES_VERSION_CONFLICT");
        verify(audit,never()).recordRequired(any());
        writable();doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());
        assertThatThrownBy(()->service.updateRules("original-key",request("UNLIMITED",null,"Controlled install upgrade")))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit unavailable");
    }
    @Test void nonSuperWithCapabilityAndSuperWithoutCapabilityFailPreflight(){
        doThrow(new BizException(403,"SUPPORT_RULES_FORBIDDEN")).when(ownership).requireSuperAdminSnapshot();
        assertThatThrownBy(()->service.updateRules("original-key",request("UNLIMITED",null,"Controlled install upgrade"))).isInstanceOf(BizException.class);
        doNothing().when(ownership).requireSuperAdminSnapshot();when(mapper.rulesWriteGrantSnapshot(7L)).thenReturn(java.util.List.of());
        assertThatThrownBy(()->service.updateRules("original-key",request("UNLIMITED",null,"Controlled install upgrade"))).isInstanceOf(BizException.class);
        verify(mapper,never()).lockRules();verifyNoInteractions(idempotency,audit);
    }
    @Test void capabilityRevokedAfterPreflightStopsMutationAndAudit(){
        when(mapper.rulesWriteGrant(7L)).thenReturn(java.util.List.of());
        assertThatThrownBy(()->service.updateRules("original-key",request("UNLIMITED",null,"Controlled install upgrade"))).isInstanceOf(BizException.class);
        verify(mapper,never()).updateRules(any(),any(),any(),any(),any(),any(),any(),any(),any());
        verifyNoInteractions(audit);
    }
}
