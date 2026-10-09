package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.*;
import ffdd.opsconsole.content.domain.SupportGroupFacts.*;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsSupportGroupCommandReadTest {
    final AdminIdempotencyRecordMapper records=mock(AdminIdempotencyRecordMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final SupportBulkService bulk=mock(SupportBulkService.class);
    final OpsSupportCommandController controller=new OpsSupportCommandController(records,ownership,new ObjectMapper(),
            mock(SupportTicketRepository.class),mock(SupportBindingRandomService.class),bulk,mock(ConversationTimeoutPolicyMapper.class));
    @BeforeEach void setup(){login("service_m1_read","platform_a1_read");when(ownership.actorId()).thenReturn(7L);
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(7L,ReadMode.MANAGED,null,null));}
    @AfterEach void logout(){SecurityContextHolder.clearContext();}
    private void login(String... authorities){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("7",null,
            Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));}
    private void receipt(String scope,String status){var row=new AdminIdempotencyRecordEntity();row.setScope(scope);row.setStatus(status);
        row.setResponseJson("not even valid json; historic private result must never be parsed");when(records.selectSupportCommand(anyList(),eq("k"))).thenReturn(List.of(row));}
    @ParameterizedTest @ValueSource(strings={"CREATE","RENAME","STATUS","OWNER","MEMBER","QUALIFICATION","ROUTE"})
    void allSevenScopesAreActorSpecificAndOnlyProjectStatus(String kind)throws Exception {
        receipt("SUPPORT_GROUP_"+kind+":7","SUCCEEDED");
        assertThat(controller.recover("k").getData()).containsExactlyEntriesOf(Map.of("status","SUCCEEDED"));
        verify(records).selectSupportCommand(argThat(s->s.contains("SUPPORT_GROUP_"+kind+":7")&&s.stream().allMatch(v->v.endsWith(":7"))),eq("k"));
        verify(ownership,never()).requireRead(anyLong());verify(ownership,never()).lockCustomer(anyLong());
    }
    @ParameterizedTest @ValueSource(strings={"PROCESSING","FAILED","UNKNOWN"})
    void uncertainAndFailedReceiptsKeepOnlyTheirActualStatus(String status)throws Exception {
        receipt("SUPPORT_GROUP_MEMBER:7",status);
        assertThat(controller.recover("k").getData()).containsExactlyEntriesOf(Map.of("status",status));verifyNoInteractions(bulk);
    }
    @Test void a1OnlyQualificationRecoveryIsPermittedWithoutAnyM1Read()throws Exception {
        login("platform_a1_read");receipt("SUPPORT_GROUP_QUALIFICATION:7","SUCCEEDED");
        assertThat(controller.recover("k").getData()).containsExactlyEntriesOf(Map.of("status","SUCCEEDED"));
        verify(records).selectSupportCommand(eq(List.of("SUPPORT_GROUP_QUALIFICATION:7")),eq("k"));verify(ownership).requireSuperAdmin();
        verify(ownership,never()).defaultQueryScope(any(),any());
    }
    @Test void a1OnlyCannotRecoverOldM1M2M3ScopesOrOtherGroupCommands(){
        login("platform_a1_read");receipt("SUPPORT_TRANSFER:7","SUCCEEDED");
        assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPPORT_COMMAND_NOT_FOUND");verifyNoInteractions(bulk);
    }
    @Test void m1ReadCannotRecoverQualificationWithoutA1Read(){
        login("service_m1_read");receipt("SUPPORT_GROUP_QUALIFICATION:7","SUCCEEDED");
        assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPPORT_COMMAND_READ_FORBIDDEN");verify(ownership,never()).requireSuperAdmin();
    }
    @Test void revokedSuperRoleCannotRecoverQualification(){
        login("platform_a1_read");receipt("SUPPORT_GROUP_QUALIFICATION:7","SUCCEEDED");
        doThrow(new BizException(403,"SUPER_ADMIN_REQUIRED")).when(ownership).requireSuperAdmin();
        assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPER_ADMIN_REQUIRED");
    }
    @Test void revokedManagementAndPersonalReaderCannotRecoverOldGroupReceipt(){
        receipt("SUPPORT_GROUP_CREATE:7","SUCCEEDED");
        doThrow(new BizException(403,"SUPPORT_SCOPE_FORBIDDEN")).when(ownership).defaultQueryScope(null,null);
        assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPPORT_SCOPE_FORBIDDEN");
        reset(ownership);when(ownership.actorId()).thenReturn(7L);when(ownership.defaultQueryScope(null,null)).thenReturn(new ReadScope(7L,ReadMode.PERSONAL,null,null));
        assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPPORT_COMMAND_READ_FORBIDDEN");
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","ROUTE"})
    void superOnlyCommandCannotRecoverAfterSuperRevocation(String kind){
        receipt("SUPPORT_GROUP_"+kind+":7","SUCCEEDED");doThrow(new BizException(403,"SUPER_ADMIN_REQUIRED")).when(ownership).requireSuperAdmin();
        assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPER_ADMIN_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings={"SUPPORT_GROUP_MEMBER:8","SUPPORT_GROUP_FAKE:7"})
    void otherActorAndUnlistedScopesAreNeverProjected(String scope){
        receipt(scope,"SUCCEEDED");assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPPORT_COMMAND_NOT_FOUND");
    }
    @Test void missingShortGroupReceiptDoesNotInvokeLegacyRecoveryOrRetry(){
        when(records.selectSupportCommand(anyList(),eq("k"))).thenReturn(List.of());
        assertThatThrownBy(()->controller.recover("k")).hasMessage("SUPPORT_COMMAND_NOT_FOUND");verifyNoInteractions(bulk);
    }
    @Test void commandPreAuthorizeIncludesA1ButRetainsOriginalServiceCapabilities()throws Exception {
        String expression=OpsSupportCommandController.class.getMethod("recover",String.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value();
        assertThat(expression).contains("platform_a1_read","service_m1_read","service_m2_read","service_m3_read");
    }
}
