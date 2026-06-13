package ffdd.compute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import ffdd.common.exception.BizException;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.OrderPaidPayload;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderPaidActivationServiceTest {
    private final OrderPaidActivationService service = new OrderPaidActivationService(mock(ComputeService.class));

    @Test
    void mapsEnrichedOrderPaidPayloadToDeviceActivationRequest() {
        OrderPaidPayload payload = new OrderPaidPayload();
        payload.setOrderNo("ORD-1");
        payload.setUserId(10001L);
        payload.setProductId(1L);
        payload.setProductCode("stellarbox-s1");
        payload.setProductTier("Entry");
        payload.setProductName("NexionBox S1");
        payload.setDeviceType("EDGE_BOX");
        payload.setGeneration(1);
        payload.setGpuModel("4x RTX 4090");
        payload.setVramTotalGb(96);
        payload.setBasePowerW(new BigDecimal("1200"));
        payload.setDcLocation("Singapore DC");
        payload.setPriceUsdtSnapshot(new BigDecimal("1299"));
        payload.setSourceChannel("ORDER");
        payload.setHashrate(new BigDecimal("12.5"));
        payload.setDailyUsdt(new BigDecimal("0.2"));
        payload.setDailyNex(new BigDecimal("8"));
        payload.setQuantity(2);

        DeviceActivateRequest request = service.toDeviceActivateRequest(payload);

        assertThat(request.getUserId()).isEqualTo(10001L);
        assertThat(request.getSourceOrderNo()).isEqualTo("ORD-1");
        assertThat(request.getProductCode()).isEqualTo("stellarbox-s1");
        assertThat(request.getProductTier()).isEqualTo("Entry");
        assertThat(request.getProductName()).isEqualTo("NexionBox S1");
        assertThat(request.getDeviceType()).isEqualTo("EDGE_BOX");
        assertThat(request.getGeneration()).isEqualTo(1);
        assertThat(request.getGpuModel()).isEqualTo("4x RTX 4090");
        assertThat(request.getVramTotalGb()).isEqualTo(96);
        assertThat(request.getBasePowerW()).isEqualByComparingTo("1200");
        assertThat(request.getDcLocation()).isEqualTo("Singapore DC");
        assertThat(request.getPriceUsdtSnapshot()).isEqualByComparingTo("1299");
        assertThat(request.getSourceChannel()).isEqualTo("ORDER");
        assertThat(request.getHashrate()).isEqualByComparingTo("12.5");
        assertThat(request.getDailyUsdt()).isEqualByComparingTo("0.2");
        assertThat(request.getDailyNex()).isEqualByComparingTo("8");
        assertThat(request.getQuantity()).isEqualTo(2);
    }

    @Test
    void suppliesSafeDefaultsForOlderOrderPaidPayloads() {
        OrderPaidPayload payload = new OrderPaidPayload();
        payload.setOrderNo("ORD-OLD");
        payload.setUserId(10001L);
        payload.setProductId(7L);

        DeviceActivateRequest request = service.toDeviceActivateRequest(payload);

        assertThat(request.getProductName()).isEqualTo("Product#7");
        assertThat(request.getDeviceType()).isEqualTo("COMPUTE");
        assertThat(request.getHashrate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(request.getQuantity()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidPayloadsBeforeCallingComputeService() {
        OrderPaidPayload payload = new OrderPaidPayload();
        payload.setOrderNo("ORD-BAD");
        payload.setUserId(10001L);
        payload.setQuantity(0);

        assertThatThrownBy(() -> service.toDeviceActivateRequest(payload))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("quantity");
    }
}
