package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.*;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.mapper.*;
import ffdd.opsconsole.user.application.NotificationTimeEvidenceService.*;
import ffdd.opsconsole.user.application.NotificationTimeCorrectionService.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class NotificationTimeCorrectionServiceTest {
    private final UserOpsRepository users=mock(UserOpsRepository.class);
    private final AdminOperatorRoleResolver roles=mock(AdminOperatorRoleResolver.class);
    private final NotificationTimeEvidenceService evidence=mock(NotificationTimeEvidenceService.class);
    private final NotificationTimeCorrectionMapper mapper=mock(NotificationTimeCorrectionMapper.class);
    private final AdminIdempotencyService idempotency=mock(AdminIdempotencyService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final NotificationTimeCorrectionService service=new NotificationTimeCorrectionService(users,roles,evidence,mapper,idempotency,audit);
    private final LocalDateTime old=LocalDateTime.of(2026,9,18,3,38,59);
    private final List<FactView> facts=List.of(new FactView("a".repeat(32),"auth.register_completed",1),new FactView("b".repeat(32),"nova.push_sent",2),new FactView("c".repeat(32),"notification.delivered",3));
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","",List.of()));
        when(roles.resolveCode()).thenReturn("SUPER_ADMIN");when(users.findUserIdByLookupKey("U7")).thenReturn(Optional.of(7L));
        when(mapper.lockNotification(7,99)).thenReturn(new NotificationTimeEvidenceMapper.NotificationRow(99L,7L,"NOVA-welcome-"+"a".repeat(32),"NOVA_WELCOME","READ",old));
        when(evidence.evaluate(any(),eq(7L),any(),any(),any())).thenReturn(ApiResult.ok(new EvidenceView(99L,"MATCHED","fixture",old,old.plusHours(8).withNano(639000000),"Asia/Shanghai",facts)));
        when(mapper.correctCreatedAt(any(),any())).thenReturn(1);
        when(idempotency.executeRepeatableRead(anyString(),anyString(),anyString(),eq(CorrectionView.class),any())).thenAnswer(call->((Supplier<?>)call.getArgument(4)).get());
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private CorrectionRequest request() { return new CorrectionRequest(old,facts,"核对独立投递事实后校正展示时间"); }
    @Test void usesServerFactAtSecondPrecisionAndRequiresAudit() {
        var result=service.correct("U7",99L,"key",request());
        assertThat(result.correctedCreatedAt()).isEqualTo(old.plusHours(8));
        verify(mapper).correctCreatedAt(any(),eq(old.plusHours(8)));verify(audit).recordRequired(argThat(r->r.getAction().equals("USER_NOTIFICATION_TIME_CORRECTED")&&r.getUserId()==7));
    }
    @Test void stalePreviewAndChangedFactsCannotWrite() {
        assertThatThrownBy(()->service.correct("U7",99L,"key",new CorrectionRequest(old.minusSeconds(1),facts,"reason"))).hasMessageContaining("SNAPSHOT_CHANGED");
        assertThatThrownBy(()->service.correct("U7",99L,"key",new CorrectionRequest(old,List.of(facts.get(0),facts.get(0),facts.get(2)),"reason"))).hasMessageContaining("EVIDENCE_CHANGED");
        verify(mapper,never()).correctCreatedAt(any(),any());verifyNoInteractions(audit);
    }
    @Test void casZeroIsConflictAndCannotAuditSuccess() {
        when(mapper.correctCreatedAt(any(),any())).thenReturn(0);
        assertThatThrownBy(()->service.correct("U7",99L,"key",request())).hasMessageContaining("SNAPSHOT_CHANGED");verifyNoInteractions(audit);
    }
    @Test void unsupportedRoleFailsBeforeIdempotencyOrEvidence() {
        when(roles.resolveCode()).thenReturn("SUPPORT");
        assertThatThrownBy(()->service.correct("U7",99L,"key",request())).hasMessageContaining("PERMISSION_DENIED");
        verifyNoInteractions(users,idempotency,evidence,mapper,audit);
    }
}
