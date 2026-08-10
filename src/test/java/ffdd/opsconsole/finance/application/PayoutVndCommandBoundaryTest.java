package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.dto.PayoutVndConfigUpdateRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PayoutVndCommandBoundaryTest {
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final PayoutVndCommandBoundary boundary = new PayoutVndCommandBoundary(idempotency);

    @Test
    @SuppressWarnings("unchecked")
    void semanticallyEquivalentOldAndNewClientPayloadsHaveTheSameHash() {
        when(idempotency.execute(anyString(), anyString(), anyString(), eq(ApiResult.class), any()))
                .thenAnswer(invocation -> ((Supplier<ApiResult<Map<String, Object>>>) invocation.getArgument(4)).get());
        PayoutVndConfigUpdateRequest oldClient = request(new BigDecimal("1"), null, "  same reason  ");
        PayoutVndConfigUpdateRequest newClient = request(new BigDecimal("1.0"), false, "same reason");

        boundary.execute("CONFIG_UPDATE", "same-key", oldClient, () -> ApiResult.ok(Map.of()));
        boundary.execute("CONFIG_UPDATE", "same-key", newClient, () -> ApiResult.ok(Map.of()));

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(idempotency, org.mockito.Mockito.times(2)).execute(
                eq("FINANCE:D7:CONFIG_UPDATE"), eq("same-key"), hashes.capture(), eq(ApiResult.class), any());
        assertThat(hashes.getAllValues()).hasSize(2);
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
    }

    private PayoutVndConfigUpdateRequest request(BigDecimal feeRate, Boolean force, String reason) {
        return new PayoutVndConfigUpdateRequest(
                new BigDecimal("1.5"), 9, new BigDecimal("2"), feeRate,
                new BigDecimal("1"), new BigDecimal("25"), new BigDecimal("20"),
                new BigDecimal("5000"), 1L, reason, force);
    }
}
