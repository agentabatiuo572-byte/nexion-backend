package ffdd.compute.service;

import ffdd.common.exception.BizException;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceActivateRequest;
import ffdd.compute.dto.OrderPaidPayload;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OrderPaidActivationService {
    private static final String DEFAULT_DEVICE_TYPE = "COMPUTE";

    private final ComputeService computeService;

    public OrderPaidActivationService(ComputeService computeService) {
        this.computeService = computeService;
    }

    public List<UserDevice> activate(OrderPaidPayload payload) {
        return computeService.activateDevices(toDeviceActivateRequest(payload));
    }

    DeviceActivateRequest toDeviceActivateRequest(OrderPaidPayload payload) {
        validate(payload);
        DeviceActivateRequest request = new DeviceActivateRequest();
        request.setUserId(payload.getUserId());
        request.setSourceOrderNo(payload.getOrderNo());
        request.setProductId(payload.getProductId());
        request.setProductCode(trimToNull(payload.getProductCode()));
        request.setProductTier(payload.getProductTier());
        request.setProductName(productName(payload));
        request.setDeviceType(StringUtils.hasText(payload.getDeviceType())
                ? payload.getDeviceType()
                : DEFAULT_DEVICE_TYPE);
        request.setGeneration(payload.getGeneration());
        request.setGpuModel(trimToNull(payload.getGpuModel()));
        request.setVramTotalGb(payload.getVramTotalGb());
        request.setBasePowerW(payload.getBasePowerW());
        request.setDcLocation(trimToNull(payload.getDcLocation()));
        request.setPriceUsdtSnapshot(payload.getPriceUsdtSnapshot());
        request.setSourceChannel(trimToNull(payload.getSourceChannel()));
        request.setHashrate(defaultDecimal(payload.getHashrate()));
        request.setDailyUsdt(defaultDecimal(payload.getDailyUsdt()));
        request.setDailyNex(defaultDecimal(payload.getDailyNex()));
        request.setQuantity(payload.getQuantity() == null ? 1 : payload.getQuantity());
        return request;
    }

    private void validate(OrderPaidPayload payload) {
        if (payload == null) {
            throw new BizException("OrderPaid payload is required");
        }
        if (!StringUtils.hasText(payload.getOrderNo())) {
            throw new BizException("OrderPaid orderNo is required");
        }
        if (payload.getUserId() == null) {
            throw new BizException("OrderPaid userId is required");
        }
        if (payload.getQuantity() != null && payload.getQuantity() < 1) {
            throw new BizException("OrderPaid quantity must be positive");
        }
    }

    private String productName(OrderPaidPayload payload) {
        if (StringUtils.hasText(payload.getProductName())) {
            return payload.getProductName();
        }
        return payload.getProductId() == null ? "Nexion Compute Device" : "Product#" + payload.getProductId();
    }

    private BigDecimal defaultDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
