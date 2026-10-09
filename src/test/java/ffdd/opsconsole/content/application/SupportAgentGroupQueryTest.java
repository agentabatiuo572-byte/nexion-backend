package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.domain.SupportAgentRepository.SupportOperatorScope;
import ffdd.opsconsole.content.dto.SupportAgentQueryRequest;
import ffdd.opsconsole.platform.application.OpsAdminAccountService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportAgentGroupQueryTest {
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final SupportAgentRepository repository=mock(SupportAgentRepository.class);
    final OpsSupportAgentService service=new OpsSupportAgentService(repository,mock(OpsAdminAccountService.class),
            mock(AuditLogService.class),mock(AdminIdempotencyService.class),OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
            Clock.systemUTC(),ownership,mock(SupportBindingService.class),mock(SupportGroupService.class));
    @Test void requestedGroupIsPassedToCurrentScopeAndSharedCountPageProjection(){
        var scope=new ReadScope(7L,ReadMode.MANAGED,8L,null);
        var operators=new SupportOperatorScope(null,List.of(),List.of(),false,scope);
        when(ownership.defaultQueryScope(8L,null)).thenReturn(scope);when(repository.scopedSupportOperators(scope)).thenReturn(operators);
        when(repository.countSupportOperators(operators)).thenReturn(1L);when(repository.pageSupportOperators(operators,20L,0L)).thenReturn(List.of());
        assertThat(service.agents(new SupportAgentQueryRequest(1L,20L,8L)).getData().total()).isEqualTo(1L);
        verify(ownership).defaultQueryScope(8L,null);verify(repository).countSupportOperators(operators);verify(repository).pageSupportOperators(operators,20L,0L);
        verify(ownership,never()).defaultQueryScope(null,null);
    }
    @Test void oldTwoArgumentConstructorKeepsOmittedGroupScope(){
        var scope=new ReadScope(7L,ReadMode.ALL,null,null);var operators=new SupportOperatorScope(null,List.of(),List.of(),false,scope);
        when(ownership.defaultQueryScope(null,null)).thenReturn(scope);when(repository.scopedSupportOperators(scope)).thenReturn(operators);
        var oldRequest=new SupportAgentQueryRequest(1L,20L);assertThat(oldRequest.groupId()).isNull();
        assertThat(service.agents(oldRequest).getData().total()).isZero();verify(ownership).defaultQueryScope(null,null);
    }
    @Test void externalOrRevokedGroupAndUnsafeIdsStopBeforeRepositoryReads(){
        for(Long group:List.of(9L,0L,9007199254740992L)) {
            when(ownership.defaultQueryScope(group,null)).thenThrow(new BizException(group==9L?404:422,"SUPPORT_SCOPE_DENIED"));
            assertThatThrownBy(()->service.agents(new SupportAgentQueryRequest(1L,20L,group))).hasMessage("SUPPORT_SCOPE_DENIED");
        }
        verifyNoInteractions(repository);
    }
}
