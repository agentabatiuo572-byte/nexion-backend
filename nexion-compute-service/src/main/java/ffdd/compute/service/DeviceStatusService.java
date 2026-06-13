package ffdd.compute.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.common.exception.BizException;
import ffdd.compute.domain.UserDeviceRuntime;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceStatusResponse;
import ffdd.compute.dto.DeviceStatusUpdateRequest;
import ffdd.compute.dto.NodeMapResponse;
import ffdd.compute.mapper.UserDeviceRuntimeMapper;
import ffdd.compute.mapper.UserDeviceMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DeviceStatusService {
    private static final String DEVICE_STATE_PREFIX = "compute:device:state:";
    private static final String DEVICE_STATE_INDEX = "compute:device:state:index";
    private static final Set<String> ALLOWED_STATUSES = Set.of("ONLINE", "BUSY", "DEGRADED", "OFFLINE");
    private static final int MAX_NODE_MAP_LIMIT = 500;

    private final UserDeviceMapper userDeviceMapper;
    private final UserDeviceRuntimeMapper runtimeMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration stateTtl;

    public DeviceStatusService(
            UserDeviceMapper userDeviceMapper,
            UserDeviceRuntimeMapper runtimeMapper,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${nexion.compute.device-state.ttl-seconds:30}") long ttlSeconds) {
        this.userDeviceMapper = userDeviceMapper;
        this.runtimeMapper = runtimeMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.stateTtl = Duration.ofSeconds(Math.max(5, ttlSeconds));
    }

    public DeviceStatusResponse reportStatus(Long deviceId, DeviceStatusUpdateRequest request) {
        UserDevice device = requireDevice(deviceId);
        String status = normalizeStatus(request.getStatus());
        DeviceStatusResponse response = DeviceStatusResponse.fromDevice(device, "UPDATED");
        response.setStatus(status);
        response.setRegion(trimToNull(request.getRegion()));
        response.setCountry(trimToNull(request.getCountry()));
        response.setCity(trimToNull(request.getCity()));
        response.setLatitude(request.getLatitude());
        response.setLongitude(request.getLongitude());
        response.setTemperatureC(request.getTemperatureC());
        response.setPowerW(request.getPowerW());
        response.setGpuUsage(request.getGpuUsage());
        response.setVramUsedGb(request.getVramUsedGb());
        response.setBatteryLevel(request.getBatteryLevel());
        response.setIsCharging(request.getIsCharging());
        response.setNetworkReachable(request.getNetworkReachable());
        response.setThermalState(trimToNull(request.getThermalState()));
        response.setPausedReason(trimToNull(request.getPausedReason()));
        response.setActiveTaskNo(trimToNull(request.getActiveTaskNo()));
        response.setClientName(trimToNull(request.getClientName()));
        response.setAgentVersion(trimToNull(request.getAgentVersion()));
        response.setReportedAt(request.getReportedAt() == null ? LocalDateTime.now() : request.getReportedAt());
        response.setLastSeenAt(response.getReportedAt());
        persistRuntime(response);
        writeState(response);
        return response;
    }

    public DeviceStatusResponse getStatus(Long deviceId) {
        try {
            String cached = redisTemplate.opsForValue().get(deviceStateKey(deviceId));
            if (StringUtils.hasText(cached)) {
                DeviceStatusResponse response = readState(cached);
                response.setCacheStatus("HIT");
                return response;
            }
        } catch (RuntimeException ignored) {
            // Redis stores high-frequency transient state; database metadata remains the safe fallback.
        }
        return DeviceStatusResponse.fromDevice(requireDevice(deviceId), "MISS");
    }

    public NodeMapResponse nodeMap(int limit) {
        int normalizedLimit = normalizeLimit(limit);
        try {
            Set<String> ids = redisTemplate.opsForSet().members(DEVICE_STATE_INDEX);
            if (ids == null || ids.isEmpty()) {
                return fallbackNodeMap(normalizedLimit);
            }
            List<DeviceStatusResponse> points = new ArrayList<>();
            ids.stream()
                    .filter(StringUtils::hasText)
                    .sorted(Comparator.comparingLong(this::parseLongOrMax))
                    .limit(normalizedLimit)
                    .forEach(id -> addCachedPoint(points, id));
            if (points.isEmpty()) {
                return fallbackNodeMap(normalizedLimit);
            }
            return summarize(points, "HIT");
        } catch (RuntimeException ex) {
            return fallbackNodeMap(normalizedLimit);
        }
    }

    public void writeTaskLifecycleState(
            UserDevice device, String status, String activeTaskNo, String clientName, LocalDateTime reportedAt) {
        if (device == null || device.getId() == null) {
            return;
        }
        try {
            DeviceStatusResponse response = DeviceStatusResponse.fromDevice(device, "UPDATED");
            response.setStatus(status);
            response.setActiveTaskNo(trimToNull(activeTaskNo));
            response.setClientName(trimToNull(clientName));
            response.setReportedAt(reportedAt == null ? LocalDateTime.now() : reportedAt);
            response.setLastSeenAt(response.getReportedAt());
            writeState(response);
        } catch (RuntimeException ignored) {
            // Scheduler state is durable in MySQL; Redis lifecycle state is a best-effort acceleration path.
        }
    }

    private void writeState(DeviceStatusResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    deviceStateKey(response.getUserDeviceId()),
                    objectMapper.writeValueAsString(response),
                    stateTtl);
            redisTemplate.opsForSet().add(DEVICE_STATE_INDEX, response.getUserDeviceId().toString());
        } catch (JsonProcessingException ex) {
            throw new BizException("Unable to serialize device state");
        }
    }

    private void persistRuntime(DeviceStatusResponse response) {
        UserDeviceRuntime runtime = new UserDeviceRuntime();
        runtime.setUserDeviceId(response.getUserDeviceId());
        runtime.setOnlineStatus(response.getStatus());
        runtime.setRegion(response.getRegion());
        runtime.setCountry(response.getCountry());
        runtime.setCity(response.getCity());
        runtime.setLatitude(response.getLatitude());
        runtime.setLongitude(response.getLongitude());
        runtime.setGpuUsage(response.getGpuUsage());
        runtime.setGpuTempC(response.getTemperatureC());
        runtime.setGpuPowerW(response.getPowerW());
        runtime.setVramUsedGb(response.getVramUsedGb());
        runtime.setBatteryLevel(response.getBatteryLevel());
        runtime.setIsCharging(toFlag(response.getIsCharging()));
        runtime.setNetworkReachable(toFlag(response.getNetworkReachable()));
        runtime.setThermalState(response.getThermalState());
        runtime.setPausedReason(response.getPausedReason());
        runtime.setActiveTaskNo(response.getActiveTaskNo());
        runtime.setClientName(response.getClientName());
        runtime.setHeartbeatAt(response.getReportedAt());
        runtime.setAgentVersion(response.getAgentVersion());
        runtimeMapper.upsert(runtime);
    }

    private Integer toFlag(Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? 1 : 0;
    }

    private void addCachedPoint(List<DeviceStatusResponse> points, String id) {
        String cached = redisTemplate.opsForValue().get(deviceStateKey(id));
        if (!StringUtils.hasText(cached)) {
            return;
        }
        DeviceStatusResponse response = readState(cached);
        response.setCacheStatus("HIT");
        points.add(response);
    }

    private NodeMapResponse fallbackNodeMap(int limit) {
        List<UserDevice> devices = userDeviceMapper.selectList(new LambdaQueryWrapper<UserDevice>()
                .eq(UserDevice::getIsDeleted, 0)
                .orderByDesc(UserDevice::getLastSeenAt)
                .last("LIMIT " + limit));
        List<DeviceStatusResponse> points = devices.stream()
                .map(device -> DeviceStatusResponse.fromDevice(device, "FALLBACK"))
                .toList();
        return summarize(points, "FALLBACK");
    }

    private NodeMapResponse summarize(List<DeviceStatusResponse> points, String cacheStatus) {
        NodeMapResponse response = new NodeMapResponse();
        response.setPoints(points);
        response.setTotal(points.size());
        response.setOnline(count(points, "ONLINE"));
        response.setBusy(count(points, "BUSY"));
        response.setDegraded(count(points, "DEGRADED"));
        response.setOffline(count(points, "OFFLINE"));
        response.setCacheStatus(cacheStatus);
        response.setGeneratedAt(LocalDateTime.now());
        return response;
    }

    private long count(List<DeviceStatusResponse> points, String status) {
        return points.stream()
                .filter(point -> status.equalsIgnoreCase(point.getStatus()))
                .count();
    }

    private DeviceStatusResponse readState(String value) {
        try {
            return objectMapper.readValue(value, DeviceStatusResponse.class);
        } catch (JsonProcessingException ex) {
            throw new BizException("Unable to read device state");
        }
    }

    private UserDevice requireDevice(Long deviceId) {
        if (deviceId == null || deviceId < 1) {
            throw new BizException("Device id is required");
        }
        UserDevice device = userDeviceMapper.selectById(deviceId);
        if (device == null || Integer.valueOf(1).equals(device.getIsDeleted())) {
            throw new BizException("Device not found");
        }
        return device;
    }

    private String normalizeStatus(String status) {
        String normalized = trimToNull(status);
        if (normalized == null) {
            throw new BizException("Device status is required");
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(normalized)) {
            throw new BizException("Unsupported device status");
        }
        return normalized;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 100;
        }
        return Math.min(limit, MAX_NODE_MAP_LIMIT);
    }

    private long parseLongOrMax(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }

    private String deviceStateKey(Long deviceId) {
        return DEVICE_STATE_PREFIX + deviceId;
    }

    private String deviceStateKey(String deviceId) {
        return DEVICE_STATE_PREFIX + deviceId;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
