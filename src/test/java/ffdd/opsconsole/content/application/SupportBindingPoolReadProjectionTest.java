package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.mapper.SupportBindingMapper;
import ffdd.opsconsole.content.mapper.SupportGroupMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.*;
import org.junit.jupiter.api.Test;

class SupportBindingPoolReadProjectionTest {
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final SupportBindingMapper mapper=mock(SupportBindingMapper.class);
    final SupportBindingService service=new SupportBindingService(mapper,ownership,mock(AdminIdempotencyService.class),
            mock(AuditLogService.class),mock(org.springframework.context.ApplicationEventPublisher.class),
            mock(SupportAgentRepository.class),mock(ProductionSupportPathGuard.class),mock(SupportTicketOwnerService.class),mock(SupportGroupMapper.class));
    final ReadScope scope=new ReadScope(7L,ReadMode.ALL,null,null);
    @Test void absentAndUnknownHaveExplicitNullableFieldsWithoutChangingPoolVersionOrImmutableSourceMap(){
        when(ownership.defaultQueryScope(null,null)).thenReturn(scope);
        var absent=Map.<String,Object>of("customerId",13L,"version",4L,"routeState","ABSENT","reason","EXHAUSTED");
        var unknown=Map.<String,Object>of("customerId",14L,"version",8L,"routeState","UNKNOWN");
        when(mapper.scopedPool(scope,null,null,0L,20)).thenReturn(List.of(absent,unknown));
        var rows=service.pool(null,null,1,20).getRecords();
        assertThat(rows).hasSize(2);assertThat(rows.get(0)).containsEntry("version",4L).containsEntry("reason","EXHAUSTED");
        for(var row:rows)assertThat(row).containsEntry("routeId",null).containsEntry("routeGroupId",null).containsEntry("routeVersion",null);
        assertThat(absent).doesNotContainKeys("routeId","routeGroupId","routeVersion");
    }
    @Test void explicitUnroutedCurrentFactPreservesNonzeroRouteVersionAndFreshRead(){
        when(ownership.defaultQueryScope(null,null)).thenReturn(scope);
        when(mapper.scopedPool(scope,null,null,0L,20)).thenReturn(List.of(Map.of("customerId",13L,"version",4L,"routeState","AVAILABLE","routeId","21","routeVersion",7L)),
                List.of(Map.of("customerId",13L,"version",4L,"routeState","AVAILABLE","routeId","22","routeGroupId","8","routeVersion",8L)));
        var first=service.pool(null,null,1,20).getRecords().get(0);
        assertThat(first).containsEntry("routeGroupId",null).containsEntry("routeVersion",7L).containsEntry("version",4L);
        assertThat(service.pool(null,null,1,20).getRecords().get(0)).containsEntry("routeGroupId","8").containsEntry("routeVersion",8L);
    }
    @Test void currentGroupScopeAndRevocationRemainMandatoryBeforePoolQueries(){
        var managed=new ReadScope(7L,ReadMode.MANAGED,8L,null);when(ownership.defaultQueryScope(8L,null)).thenReturn(managed);
        when(mapper.scopedPool(managed,null,null,0L,20)).thenReturn(List.of());service.pool(null,null,8L,1,20);
        verify(mapper).scopedPool(managed,null,null,0L,20);
        doThrow(new BizException(404,"SUPPORT_GROUP_NOT_FOUND")).when(ownership).defaultQueryScope(8L,null);
        assertThatThrownBy(()->service.pool(null,null,8L,1,20)).hasMessage("SUPPORT_GROUP_NOT_FOUND");
        verify(mapper,times(1)).scopedPool(managed,null,null,0L,20);
    }
}
