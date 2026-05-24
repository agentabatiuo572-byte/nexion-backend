package ffdd.compute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.compute.domain.UserDevice;
import ffdd.compute.dto.DeviceStatusResponse;
import ffdd.compute.dto.DeviceStatusUpdateRequest;
import ffdd.compute.dto.NodeMapResponse;
import ffdd.compute.mapper.UserDeviceMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class DeviceStatusServiceTest {
    private final UserDeviceMapper userDeviceMapper = mock(UserDeviceMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final SetOperations<String, String> setOps = mock(SetOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DeviceStatusService service =
            new DeviceStatusService(userDeviceMapper, redisTemplate, objectMapper, 45);

    @Test
    void reportStatusWritesRealtimeStateToRedisWithoutPersistingEveryMetric() throws Exception {
        stubRedis();
        when(userDeviceMapper.selectById(7L)).thenReturn(device(7L, 10001L, "ONLINE"));
        DeviceStatusUpdateRequest request = new DeviceStatusUpdateRequest();
        request.setStatus("BUSY");
        request.setRegion("North America");
        request.setCountry("US");
        request.setCity("San Jose");
        request.setLatitude(new BigDecimal("37.3382"));
        request.setLongitude(new BigDecimal("-121.8863"));
        request.setTemperatureC(new BigDecimal("61.5"));
        request.setPowerW(new BigDecimal("320.0"));
        request.setGpuUsage(new BigDecimal("87.5"));
        request.setActiveTaskNo("TASK-1");
        request.setClientName("worker-a");
        request.setReportedAt(LocalDateTime.of(2026, 5, 24, 23, 50));

        DeviceStatusResponse response = service.reportStatus(7L, request);

        assertThat(response.getStatus()).isEqualTo("BUSY");
        assertThat(response.getCacheStatus()).isEqualTo("UPDATED");
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("compute:device:state:7"), valueCaptor.capture(), eq(Duration.ofSeconds(45)));
        DeviceStatusResponse cached = objectMapper.readValue(valueCaptor.getValue(), DeviceStatusResponse.class);
        assertThat(cached.getRegion()).isEqualTo("North America");
        assertThat(cached.getGpuUsage()).isEqualByComparingTo("87.5");
        verify(setOps).add("compute:device:state:index", "7");
        verify(userDeviceMapper, never()).updateById(org.mockito.ArgumentMatchers.any(UserDevice.class));
    }

    @Test
    void getStatusFallsBackToDatabaseWhenRedisStateIsMissing() {
        stubRedis();
        when(valueOps.get("compute:device:state:7")).thenReturn(null);
        when(userDeviceMapper.selectById(7L)).thenReturn(device(7L, 10001L, "ONLINE"));

        DeviceStatusResponse response = service.getStatus(7L);

        assertThat(response.getStatus()).isEqualTo("ONLINE");
        assertThat(response.getCacheStatus()).isEqualTo("MISS");
        assertThat(response.getUserId()).isEqualTo(10001L);
    }

    @Test
    void nodeMapReadsCachedStatesAndBuildsSummary() throws Exception {
        stubRedis();
        DeviceStatusResponse busy = cachedStatus(7L, "BUSY", "US", "San Jose");
        DeviceStatusResponse online = cachedStatus(8L, "ONLINE", "SG", "Singapore");
        when(setOps.members("compute:device:state:index")).thenReturn(Set.of("7", "8"));
        when(valueOps.get("compute:device:state:7")).thenReturn(objectMapper.writeValueAsString(busy));
        when(valueOps.get("compute:device:state:8")).thenReturn(objectMapper.writeValueAsString(online));

        NodeMapResponse response = service.nodeMap(10);

        assertThat(response.getCacheStatus()).isEqualTo("HIT");
        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getOnline()).isEqualTo(1);
        assertThat(response.getBusy()).isEqualTo(1);
        assertThat(response.getPoints()).extracting(DeviceStatusResponse::getUserDeviceId)
                .containsExactly(7L, 8L);
    }

    @Test
    void nodeMapFallsBackToDatabaseWhenRedisIsUnavailable() {
        stubRedis();
        when(setOps.members("compute:device:state:index")).thenThrow(new IllegalStateException("redis down"));
        when(userDeviceMapper.selectList(org.mockito.ArgumentMatchers.<Wrapper<UserDevice>>any()))
                .thenReturn(List.of(device(7L, 10001L, "ONLINE"), device(8L, 10002L, "OFFLINE")));

        NodeMapResponse response = service.nodeMap(10);

        assertThat(response.getCacheStatus()).isEqualTo("FALLBACK");
        assertThat(response.getTotal()).isEqualTo(2);
        assertThat(response.getOnline()).isEqualTo(1);
        assertThat(response.getOffline()).isEqualTo(1);
    }

    @Test
    void writeTaskLifecycleStateCachesSchedulerStateBestEffort() throws Exception {
        stubRedis();
        UserDevice device = device(7L, 10001L, "ONLINE");
        LocalDateTime reportedAt = LocalDateTime.of(2026, 5, 25, 0, 40);

        service.writeTaskLifecycleState(device, "BUSY", "TASK-1", "scheduler-a", reportedAt);

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("compute:device:state:7"), valueCaptor.capture(), eq(Duration.ofSeconds(45)));
        DeviceStatusResponse cached = objectMapper.readValue(valueCaptor.getValue(), DeviceStatusResponse.class);
        assertThat(cached.getStatus()).isEqualTo("BUSY");
        assertThat(cached.getActiveTaskNo()).isEqualTo("TASK-1");
        assertThat(cached.getClientName()).isEqualTo("scheduler-a");
        assertThat(cached.getReportedAt()).isEqualTo(reportedAt);
    }

    @Test
    void writeTaskLifecycleStateDoesNotFailWhenRedisIsUnavailable() {
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        service.writeTaskLifecycleState(
                device(7L, 10001L, "ONLINE"),
                "BUSY",
                "TASK-1",
                "scheduler-a",
                LocalDateTime.of(2026, 5, 25, 0, 40));
    }

    private void stubRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
    }

    private UserDevice device(Long id, Long userId, String status) {
        UserDevice device = new UserDevice();
        device.setId(id);
        device.setUserId(userId);
        device.setInstanceNo("UD-" + id);
        device.setName("AI Node " + id);
        device.setDeviceType("GPU");
        device.setStatus(status);
        device.setHashrate(new BigDecimal("120.000000"));
        device.setDailyUsdt(new BigDecimal("2.500000"));
        device.setDailyNex(new BigDecimal("50.000000"));
        device.setLastSeenAt(LocalDateTime.of(2026, 5, 24, 23, 45));
        device.setIsDeleted(0);
        return device;
    }

    private DeviceStatusResponse cachedStatus(Long deviceId, String status, String country, String city) {
        DeviceStatusResponse response = DeviceStatusResponse.fromDevice(device(deviceId, 10000L + deviceId, status), "HIT");
        response.setCountry(country);
        response.setCity(city);
        response.setLatitude(new BigDecimal("1.3000"));
        response.setLongitude(new BigDecimal("103.8000"));
        response.setReportedAt(LocalDateTime.of(2026, 5, 24, 23, 50));
        return response;
    }
}
