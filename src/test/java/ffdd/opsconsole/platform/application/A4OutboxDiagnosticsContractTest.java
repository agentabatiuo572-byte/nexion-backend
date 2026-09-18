package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.platform.mapper.A4OutboxDiagnosticsMapper;
import ffdd.opsconsole.platform.mapper.A4OutboxDiagnosticsMapper.*;
import ffdd.opsconsole.platform.web.OpsEventCenterController;
import ffdd.opsconsole.platform.application.*;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.H3DeadLetterRedriveService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.Test;

class A4OutboxDiagnosticsContractTest {
    final A4OutboxDiagnosticsMapper mapper = mock(A4OutboxDiagnosticsMapper.class);
    final A4OutboxDiagnosticsService service = new A4OutboxDiagnosticsService(mapper);
    final LocalDateTime at = LocalDateTime.of(2026,9,8,0,0);
    EventRow row(long id) { return new EventRow(id, String.format("%032x", id), "ADMIN_USER_PROFILE_VIEWED", "PENDING", 0, at, null, null, true); }
    void stub() { when(mapper.summary()).thenReturn(new Summary(188,6,851807)); when(mapper.groups()).thenReturn(List.of()); }

    @Test void boundedKeysetUsesLastVisibleRowAndKeepsMissingReceiptDistinct() {
        stub(); when(mapper.page(0, null, null, false, 3)).thenReturn(List.of(row(9007199254740993L), row(9007199254740994L), row(9007199254740995L)));
        when(mapper.receipts(anyList())).thenReturn(List.of());
        var result = service.read(null,null,false,"0",2);
        assertThat(result.total()).isEqualTo(188); assertThat(result.unresolved()).isEqualTo(6);
        assertThat(result.nextCursor()).isEqualTo("9007199254740994"); assertThat(result.hasMore()).isTrue();
        assertThat(result.rows()).hasSize(2).allSatisfy(r -> assertThat(r.receipts()).isEmpty());
    }
    @Test void invalidFiltersFailBeforeAnyQuery() {
        for (String id : List.of("-1", "01", "9223372036854775808", "1 OR 1=1")) {
            assertThatThrownBy(() -> service.read(null,null,false,id,25)).hasMessageContaining("A4_OUTBOX_FILTER_INVALID");
        }
        assertThatThrownBy(() -> service.read(null,"DEAD",false,"0",25)).hasMessageContaining("A4_OUTBOX_FILTER_INVALID");
        assertThatThrownBy(() -> service.read("bad@example.com",null,false,"0",25)).hasMessageContaining("A4_OUTBOX_FILTER_INVALID");
        assertThatThrownBy(() -> service.read(null,null,false,"0",51)).hasMessageContaining("A4_OUTBOX_FILTER_INVALID");
        verifyNoInteractions(mapper);
    }
    @Test void emptyPageDoesNotFetchUnboundedReceiptsAndGroupsAreExplicitlyTruncated() {
        stub(); when(mapper.groups()).thenReturn(IntStream.range(0,201).mapToObj(i -> new GroupRow("known."+i,"PENDING",1,at,0)).toList());
        when(mapper.page(anyLong(),any(),any(),anyBoolean(),anyInt())).thenReturn(List.of());
        var result = service.read(null,null,false,"0",25);
        assertThat(result.groups()).hasSize(200); assertThat(result.groupsTruncated()).isTrue();
        assertThat(result.rows()).isEmpty(); assertThat(result.nextCursor()).isNull(); verify(mapper,never()).receipts(any());
    }
    @Test void queryFailureNeverReturnsEmptySuccess() {
        when(mapper.summary()).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> service.read(null,null,false,"0",25)).isInstanceOf(IllegalStateException.class);
    }
    @Test void receiptAssociationUsesRealIdBeforeSafeProjection() {
        stub(); when(mapper.page(anyLong(),any(),any(),anyBoolean(),anyInt())).thenReturn(List.of(
                new EventRow(1,"secret-a", "UNREGISTERED_EVENT_TYPE","PENDING",0,at,null,"OTHER_ERROR",false),
                new EventRow(2,"secret-b", "UNREGISTERED_EVENT_TYPE","PENDING",0,at,null,null,false)));
        when(mapper.receipts(any())).thenReturn(List.of(new ReceiptRow("secret-a","FAILED",1),new ReceiptRow("SECRET-B","SUCCESS",1)));
        var result = service.read(null,null,false,"0",25);
        assertThat(result.rows().get(0).eventId()).isEqualTo("INVALID_EVENT_ID");
        assertThat(result.rows().get(0).receipts()).isEmpty();
        assertThat(result.rows().get(1).receipts()).isEmpty();
        assertThat(result.toString()).doesNotContain("secret-a", "secret-b");
        verify(mapper,never()).receipts(any());
    }
    @Test void realMethodSecurityRequiresA4ReadAndNotA4WriteOrA2Read() {
        try (var context = new AnnotationConfigApplicationContext(SecurityFixture.class)) {
            var controller = context.getBean(OpsEventCenterController.class);
            var queries = context.getBean(A4OutboxDiagnosticsService.class);
            for (String authority : List.of("platform_a4_write", "platform_a2_read")) {
                SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator", "unused", authority));
                assertThatThrownBy(() -> controller.outboxDiagnostics(null,null,false,"0",25)).isInstanceOf(AccessDeniedException.class);
            }
            verifyNoInteractions(queries);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator", "unused", "platform_a4_read"));
            controller.outboxDiagnostics(null,null,false,"0",25);
            verify(queries).read(null,null,false,"0",25);
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Configuration @EnableMethodSecurity static class SecurityFixture {
        @Bean A4OutboxDiagnosticsService query() { return mock(A4OutboxDiagnosticsService.class); }
        @Bean OpsEventCenterController controller(A4OutboxDiagnosticsService query) {
            return new OpsEventCenterController(mock(OpsEventCenterService.class), mock(A4EventRetentionService.class),
                    mock(A2RuntimePolicy.class), mock(AuditLogService.class), mock(AdminIdempotencyService.class),
                    mock(H3DeadLetterRedriveService.class), new com.fasterxml.jackson.databind.ObjectMapper(), query);
        }
    }
}
