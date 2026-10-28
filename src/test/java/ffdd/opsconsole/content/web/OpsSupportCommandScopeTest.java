package ffdd.opsconsole.content.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsSupportCommandScopeTest {
    final AdminIdempotencyRecordMapper records=mock(AdminIdempotencyRecordMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper timeoutPolicy=mock(ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper.class);
    final OpsSupportCommandController controller=new OpsSupportCommandController(records,ownership,new ObjectMapper(),
            mock(SupportBindingRandomService.class),mock(SupportBulkService.class),timeoutPolicy);
    @BeforeEach void login() {
        when(ownership.actorId()).thenReturn(7L);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7",null,
                List.of(new SimpleGrantedAuthority("service_m1_read"),new SimpleGrantedAuthority("service_m3_read"))));
    }
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    @Test void oldPrivateCommandRechecksCurrentObjectBeforeReturningStoredBody() {
        receipt("M3_CONVERSATION_REPLY:7");
        doThrow(new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND")).when(ownership).requireRead(10L);
        assertThatThrownBy(()->controller.recover("retained-key")).isInstanceOfSatisfying(BizException.class,e->assertThat(e.getCode()).isEqualTo(404));
        var order=inOrder(ownership);order.verify(ownership).actorId();order.verify(ownership).lockCustomer(10L);order.verify(ownership).requireRead(10L);
    }
    @Test void retainedManagementResultCannotUsePersonalUnionToEscapeItsOldGroup() {
        receipt("SUPPORT_TRANSFER:7");
        doThrow(new BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND")).when(ownership).requireManagingCustomer(10L);
        assertThatThrownBy(()->controller.recover("retained-key")).isInstanceOf(BizException.class);
        verify(ownership,never()).requireRead(anyLong());
    }
    @Test void sameAuthorizedPrivateCommandPreservesOriginalReceipt()throws Exception {
        receipt("M3_CONVERSATION_REPLY:7");
        var result=controller.recover("retained-key");
        assertThat(result.getData()).containsEntry("status","SUCCEEDED");
        assertThat(result.getData().get("result").toString()).contains("original-private-body");
        verify(records).selectSupportCommand(argThat(s->s.stream().allMatch(v->v.endsWith(":7"))),eq("retained-key"));
    }
    @Test void timeoutRecoveryRequiresCurrentRoleAndCapabilityAndReturnsOnlyStatus() throws Exception {
        when(ownership.currentSuperAdmin()).thenReturn(true);
        when(timeoutPolicy.timeoutManageGrant(7L)).thenReturn(List.of(11L));
        receipt("M3_CONVERSATION_TIMEOUT_POLICY");
        assertThat(controller.recover("retained-key").getData()).containsExactlyEntriesOf(Map.of("status","SUCCEEDED"));
        verify(records).selectSupportCommand(argThat(s->s.contains("M3_CONVERSATION_TIMEOUT_POLICY")
                && s.stream().filter(v->!v.endsWith(":7")).toList().equals(List.of("M3_CONVERSATION_TIMEOUT_POLICY"))),eq("retained-key"));
    }
    @Test void timeoutCapabilityWithoutSuperRoleNeverIncludesGlobalKeys() throws Exception {
        when(ownership.currentSuperAdmin()).thenReturn(false);
        receipt("M3_CONVERSATION_REPLY:7");
        controller.recover("retained-key");
        verify(records).selectSupportCommand(argThat(s->!s.contains("M3_CONVERSATION_TIMEOUT_POLICY")),eq("retained-key"));
        verifyNoInteractions(timeoutPolicy);
    }
    @Test void superRoleWithoutCurrentTimeoutCapabilityNeverIncludesGlobalKeys() throws Exception {
        when(ownership.currentSuperAdmin()).thenReturn(true);
        when(timeoutPolicy.timeoutManageGrant(7L)).thenReturn(List.of());
        receipt("M3_CONVERSATION_REPLY:7");
        controller.recover("retained-key");
        verify(records).selectSupportCommand(argThat(s->!s.contains("M3_CONVERSATION_TIMEOUT_POLICY")),eq("retained-key"));
    }
    @Test void timeoutRecoveryRechecksCapabilityBeforeProjectingReceipt() {
        when(ownership.currentSuperAdmin()).thenReturn(true);
        when(timeoutPolicy.timeoutManageGrant(7L)).thenReturn(List.of(11L),List.of());
        receipt("M3_CONVERSATION_TIMEOUT_POLICY");
        assertThatThrownBy(()->controller.recover("retained-key")).isInstanceOfSatisfying(BizException.class,
                e->assertThat(e.getCode()).isEqualTo(403));
    }
    @Test void retiredTicketReceiptsAreFoundButAnswer410WithoutReplayingTheStoredBody() {
        for(String scope:List.of("M2_SUPPORT_TICKET_CREATE:7","M2_SUPPORT_TICKET_REPLY:7","M2_SUPPORT_TICKET_ESCALATE:7","M3_CONVERSATION_TO_TICKET:7")) {
            receipt(scope);
            assertThatThrownBy(()->controller.recover("retained-key")).as(scope).isInstanceOfSatisfying(BizException.class,e->{
                assertThat(e.getCode()).isEqualTo(410);assertThat(e.getMessage()).isEqualTo("SUPPORT_TICKETS_RETIRED");});
        }
        verify(records,atLeastOnce()).selectSupportCommand(argThat(s->s.containsAll(List.of("M2_SUPPORT_TICKET_REPLY:7","M3_CONVERSATION_TO_TICKET:7"))),eq("retained-key"));
        verify(ownership,never()).lockCustomer(anyLong());
        verify(ownership,never()).requireRead(anyLong());
    }
    private void receipt(String scope) {
        var row=new AdminIdempotencyRecordEntity();row.setScope(scope);row.setStatus("SUCCEEDED");
        row.setResponseJson("{\"data\":{\"customerId\":10,\"content\":\"original-private-body\"}}");
        when(records.selectSupportCommand(anyList(),eq("retained-key"))).thenReturn(List.of(row));
    }
}
