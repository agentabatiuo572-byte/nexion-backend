package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.auth.mapper.PaymentPasswordMapper;
import ffdd.opsconsole.auth.mapper.PaymentSecurityMapper;
import ffdd.opsconsole.content.application.SupportOwnershipService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsPaymentSecurityServiceTest {
    final PaymentPasswordMapper credentials=mock(PaymentPasswordMapper.class);
    final PaymentSecurityMapper repo=mock(PaymentSecurityMapper.class);
    final SupportOwnershipService ownership=mock(SupportOwnershipService.class);
    final AuditLogService audit=mock(AuditLogService.class);
    final Clock clock=Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"),ZoneOffset.UTC);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final OpsPaymentSecurityService service=new OpsPaymentSecurityService(credentials,repo,ownership,audit,json,clock);
    final String record="PINLOCK-abc",key="release-command";
    @BeforeEach void fixture() {
        authenticate(OpsPaymentSecurityService.RELEASE);
        when(ownership.actorId()).thenReturn(9L);
        when(credentials.user(7L,false)).thenReturn(Map.of("id",7L));
        when(repo.record(eq(record),anyBoolean())).thenReturn(row("ACTIVE",0L));
        when(credentials.credential(7L,true)).thenReturn(Map.of("locked_until",clock.millis()+900000));
        when(repo.command(9L,"global",key,true)).thenReturn(command());
        when(repo.command(9L,"support",key,true)).thenReturn(command());
        when(repo.release(record,7L,0L)).thenReturn(1);
    }
    @AfterEach void clearContext() {SecurityContextHolder.clearContext();}
    void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("9",null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
    Map<String,Object> row(String state,long version) {return Map.of("record_no",record,"user_id",7L,"version",version,
            "type","PAYMENT_PASSWORD_LOCK","state",state,"reason","支付密码连续错误达到限制","started_at",clock.millis()-1000,
            "locked_until",clock.millis()+900000,"failed_attempts",5,"window_minutes",15);}
    OpsPaymentSecurityService.ReleaseRequest request() {return new OpsPaymentSecurityService.ReleaseRequest("7",record,"RELEASE_LOCK",0L,"客户身份已经独立核实",true);}
    Map<String,Object> command() {
        try{return Map.of("request_hash",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request()))),"user_id",7L,"record_no",record);}
        catch(Exception e){throw new RuntimeException(e);}
    }
    @Test void loginUnlockAuthorityCannotReleasePaymentLock() {
        authenticate("user_c5_unlock_short","user_c5_unlock_long","user_c5_write");
        assertThatThrownBy(()->service.release(record,null,false,key,request())).hasMessage("PAYMENT_PASSWORD_PERMISSION_DENIED");
        verifyNoInteractions(repo,credentials,audit);
    }
    @Test void releaseUsesCasClearsOnlyPaymentCountersAndWritesRequiredAudit() {
        when(repo.record(record,false)).thenReturn(row("ACTIVE",0L),row("RELEASED",1L));
        Map<String,Object> receipt=service.release(record,null,false,key,request());
        assertThat(receipt).containsEntry("status","APPLIED").containsEntry("userId","7").containsEntry("recordId",record);
        verify(repo).release(record,7L,0L); verify(credentials).clearAttemptFailures(7L);
        verify(credentials).clearFailures(7L,clock.millis());verify(audit).recordRequired(any());
        verify(credentials,never()).writePassword(anyLong(),any(),any(),anyLong(),any());
    }
    @Test void staleVersionStoresARejectedReceiptWithoutClearingCounters() {
        when(repo.record(record,true)).thenReturn(row("ACTIVE",1L));
        assertThat(service.release(record,null,false,key,request())).containsEntry("status","REJECTED");
        verify(repo,never()).release(anyString(),anyLong(),anyLong());verify(credentials,never()).clearAttemptFailures(anyLong());
        verifyNoInteractions(audit);verify(repo).finish(eq(9L),eq("global"),eq(key),contains("REJECTED"));
    }
    @Test void supportOwnershipIsRecheckedAfterLocksAndCrossCustomerRecordIsHidden() {
        doNothing().doThrow(new ffdd.opsconsole.shared.exception.BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND")).when(ownership).requireRead(7L);
        assertThatThrownBy(()->service.release(record,7L,true,key,request())).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
        verify(repo,never()).release(anyString(),anyLong(),anyLong());
        reset(ownership);when(credentials.user(8L,false)).thenReturn(Map.of("id",8L));
        assertThatThrownBy(()->service.detail(record,8L,true)).hasMessage("PAYMENT_PASSWORD_RECORD_NOT_FOUND");
    }
    @Test void replayReturnsTheOriginalReceiptWithoutAnotherReleaseOrAudit() throws Exception {
        Map<String,Object> original=new LinkedHashMap<>(command());original.put("response_json",json.writeValueAsString(Map.of("status","APPLIED","commandKey",key)));
        when(repo.command(9L,"global",key,true)).thenReturn(original);
        assertThat(service.release(record,null,false,key,request())).containsEntry("status","APPLIED");
        verify(repo,never()).release(anyString(),anyLong(),anyLong());verifyNoInteractions(audit);
    }
    @Test void listAcceptsFrontendAllStateAndBusinessDateBounds() {
        when(repo.records(isNull(),eq(""),eq(""),anyLong(),anyLong(),anyLong(),eq(10),eq(0L))).thenReturn(List.of());
        assertThat(service.list(null,false,"","ALL","2026-10-31","2026-10-31",1,10)).containsEntry("total",0L);
        verify(repo).count(isNull(),eq(""),eq(""),anyLong(),anyLong(),eq(clock.millis()));
    }
}
