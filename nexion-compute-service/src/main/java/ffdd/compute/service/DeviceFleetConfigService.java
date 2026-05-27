package ffdd.compute.service;

import ffdd.common.api.ApiResult;
import ffdd.compute.client.SystemConfigClient;
import ffdd.compute.dto.DeviceFleetConfigResponse;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DeviceFleetConfigService {
    private static final int MIN_ACTIVE_SLOTS = 1;
    private static final int MAX_ACTIVE_SLOTS = 100;

    private final SystemConfigClient systemConfigClient;
    private final int fallbackMaxActiveSlots;

    public DeviceFleetConfigService(
            SystemConfigClient systemConfigClient,
            @Value("${nexion.compute.device-fleet.default-active-slots:6}") int fallbackMaxActiveSlots) {
        this.systemConfigClient = systemConfigClient;
        this.fallbackMaxActiveSlots = normalize(fallbackMaxActiveSlots, 6);
    }

    public DeviceFleetConfigResponse currentConfig() {
        return new DeviceFleetConfigResponse(maxActiveSlots());
    }

    public int maxActiveSlots() {
        try {
            ApiResult<Map<String, Object>> response = systemConfigClient.deviceFleet();
            if (response == null || response.getCode() != 0 || response.getData() == null) {
                return fallbackMaxActiveSlots;
            }
            return normalize(response.getData().get("maxActiveSlots"), fallbackMaxActiveSlots);
        } catch (RuntimeException ex) {
            return fallbackMaxActiveSlots;
        }
    }

    private int normalize(Object raw, int fallback) {
        Integer value = toInteger(raw);
        if (value == null || value < MIN_ACTIVE_SLOTS || value > MAX_ACTIVE_SLOTS) {
            return fallback;
        }
        return value;
    }

    private Integer toInteger(Object raw) {
        if (raw instanceof Integer value) {
            return value;
        }
        if (raw instanceof Long value) {
            return value.intValue();
        }
        if (raw instanceof BigDecimal value) {
            return value.intValue();
        }
        if (raw instanceof Number value) {
            return value.intValue();
        }
        if (raw instanceof String value) {
            try {
                return Integer.valueOf(value.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
