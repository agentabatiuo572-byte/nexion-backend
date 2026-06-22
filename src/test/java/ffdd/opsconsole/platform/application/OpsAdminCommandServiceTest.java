package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.dto.AdminCommandRequest;
import ffdd.opsconsole.platform.dto.AdminCommandResponse;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsAdminCommandServiceTest {
    private final FakeConfigFacade configFacade = new FakeConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final OpsAdminCommandService service = new OpsAdminCommandService(
            configFacade,
            auditLogService,
            idempotencyService,
            new ObjectMapper().findAndRegisterModules());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(idempotencyService.execute(anyString(), anyString(), anyString(), eq(AdminCommandResponse.class), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<AdminCommandResponse>) invocation.getArgument(4)).get());
    }

    @Test
    void acceptsCommandAndPersistsParamWhenParamKeyIsPresent() {
        AdminCommandRequest request = new AdminCommandRequest(
                "E", "订单退款 ORD-1", "ORDER", "ORD-1", "superadmin", "customer request",
                "E.order.ORD-1.refunded", "true", Map.of("source", "pc"));

        ApiResult<AdminCommandResponse> result = service.accept("idem-command", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().paramPersisted()).isTrue();
        assertThat(configFacade.values).containsEntry("ops.E.order.ORD-1.refunded", "true");
        verify(auditLogService).record(org.mockito.ArgumentMatchers.any(AuditLogWriteRequest.class));
    }

    @Test
    void writeCommandRequiresIdempotencyKeyAndReason() {
        AdminCommandRequest request = new AdminCommandRequest("E", "删除 SKU", "SKU", "sku-1", "superadmin", "", null, null, Map.of());

        ApiResult<AdminCommandResponse> result = service.accept(null, request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    private static final class FakeConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new java.util.LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
        }
    }
}
