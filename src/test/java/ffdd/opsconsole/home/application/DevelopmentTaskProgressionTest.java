package ffdd.opsconsole.home.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentActiveTask;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentRunningTask;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentTaskConfig;
import ffdd.opsconsole.home.mapper.DevelopmentHomeSettlementMapper.DevelopmentTaskDevice;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DevelopmentTaskProgressionTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-27T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 12, 0);

    @Test
    void assignsAnIndependentTaskFromEachDevicesEligibleE2Pool() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                task("EM-8", "EM", 8, "0.010000", "0.090000"),
                task("VG-48", "VG", 48, "0.400000", "0.600000")));
        when(mapper.developmentTaskDevices()).thenReturn(List.of(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12),
                device(91L, 8102L, "SERVER", "stellarrack-p1", 48)));
        when(mapper.developmentCompletedTaskCount(91L, 8101L)).thenReturn(0L);
        when(mapper.developmentCompletedTaskCount(91L, 8102L)).thenReturn(1L);
        when(mapper.lockDevelopmentTaskDevice(91L, 8101L)).thenReturn(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12));
        when(mapper.lockDevelopmentTaskDevice(91L, 8102L)).thenReturn(
                device(91L, 8102L, "SERVER", "stellarrack-p1", 48));
        when(mapper.insertDevelopmentRunningTask(any())).thenReturn(1);

        DevelopmentHomeSettlementBootstrap bootstrap = bootstrap(mapper);

        assertThat(bootstrap.advanceTasks()).isEqualTo(2);

        ArgumentCaptor<DevelopmentRunningTask> tasks = ArgumentCaptor.forClass(DevelopmentRunningTask.class);
        verify(mapper, org.mockito.Mockito.times(2)).insertDevelopmentRunningTask(tasks.capture());
        assertThat(tasks.getAllValues()).extracting(DevelopmentRunningTask::userDeviceId)
                .containsExactly(8101L, 8102L);
        assertThat(tasks.getAllValues()).extracting(DevelopmentRunningTask::taskClass)
                .containsExactly("EM", "VG");
        assertThat(tasks.getAllValues()).allSatisfy(task -> {
            assertThat(task.startedAt()).isEqualTo(NOW);
            assertThat(task.rewardUsdt()).isPositive();
        });
        var ordering = inOrder(mapper);
        ordering.verify(mapper).lockDevelopmentTaskDevice(91L, 8101L);
        ordering.verify(mapper).lockDevelopmentActiveTask(91L, 8101L);
    }

    @Test
    void completesOnlyAfterProgressEndsThenPostsReceiptWalletLedgerAndNextTaskOnce() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                task("EM-8", "EM", 8, "0.900000", "1.100000")));
        when(mapper.developmentTaskDevices()).thenReturn(List.of(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12)));
        when(mapper.lockDevelopmentActiveTask(91L, 8101L)).thenReturn(new DevelopmentActiveTask(
                "DEV-TASK-OLD", 91L, 8101L, "EM-8", "Embedding", "EM", "BGE-M3",
                "NexGrid Development Workload", new BigDecimal("0.050000"), 5,
                NOW.minusSeconds(6)));
        when(mapper.lockDevelopmentTaskDevice(91L, 8101L)).thenReturn(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12));
        when(mapper.completeDevelopmentTask(91L, 8101L, "DEV-TASK-OLD", NOW)).thenReturn(1);
        when(mapper.insertDevelopmentTaskReceipt(any())).thenReturn(1);
        when(mapper.creditDevelopmentWallet(91L, "DEV-TASK-OLD", new BigDecimal("0.050000"), NOW))
                .thenReturn(1);
        when(mapper.developmentWalletUsdt(91L)).thenReturn(new BigDecimal("2.050000"));
        when(mapper.insertDevelopmentWalletLedger(91L, "DEV-TASK-OLD", new BigDecimal("0.050000"),
                new BigDecimal("2.050000"), NOW)).thenReturn(1);
        when(mapper.insertDevelopmentEarningEvent("EARN-DEV-TASK-OLD", 91L, 8101L,
                "R-DEV-TASK-OLD", new BigDecimal("0.050000"), NOW)).thenReturn(1);
        when(mapper.insertDevelopmentRunningTask(any())).thenReturn(1);

        DevelopmentHomeSettlementBootstrap bootstrap = bootstrap(mapper);

        assertThat(bootstrap.advanceTasks()).isEqualTo(2);

        verify(mapper).insertDevelopmentTaskReceipt(any());
        verify(mapper).creditDevelopmentWallet(91L, "DEV-TASK-OLD", new BigDecimal("0.050000"), NOW);
        verify(mapper).insertDevelopmentWalletLedger(91L, "DEV-TASK-OLD", new BigDecimal("0.050000"),
                new BigDecimal("2.050000"), NOW);
        verify(mapper).insertDevelopmentEarningEvent("EARN-DEV-TASK-OLD", 91L, 8101L,
                "R-DEV-TASK-OLD", new BigDecimal("0.050000"), NOW);
        ArgumentCaptor<DevelopmentRunningTask> nextTask = ArgumentCaptor.forClass(DevelopmentRunningTask.class);
        verify(mapper).insertDevelopmentRunningTask(nextTask.capture());
        assertThat(nextTask.getValue().rewardUsdt()).isEqualByComparingTo("1.000000");
    }

    @Test
    void leavesMoneyUntouchedWhileTaskProgressIsIncomplete() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                task("EM-8", "EM", 8, "0.010000", "0.090000")));
        when(mapper.developmentTaskDevices()).thenReturn(List.of(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12)));
        when(mapper.lockDevelopmentActiveTask(91L, 8101L)).thenReturn(new DevelopmentActiveTask(
                "DEV-TASK-RUNNING", 91L, 8101L, "EM-8", "Embedding", "EM", "BGE-M3",
                "NexGrid Development Workload", new BigDecimal("0.050000"), 30,
                NOW.minusSeconds(5)));
        when(mapper.lockDevelopmentTaskDevice(91L, 8101L)).thenReturn(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12));

        assertThat(bootstrap(mapper).advanceTasks()).isZero();

        verify(mapper, never()).completeDevelopmentTask(anyLong(), anyLong(), any(), any());
        verify(mapper, never()).insertDevelopmentTaskReceipt(any());
        verify(mapper, never()).creditDevelopmentWallet(any(), any(), any(), any());
    }

    @Test
    void failsClosedBeforeTaskOrMoneyWritesWhenE3ConfigIsIncomplete() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(List.of(
                new DevelopmentHomeSettlementMapper.DevelopmentE3CapacityConfig(
                        "capacityBand1DeltaPct", "-3")));

        assertThat(bootstrap(mapper).advanceTasks()).isZero();

        verify(mapper, never()).developmentTaskDevices();
        verify(mapper, never()).insertDevelopmentRunningTask(any());
        verify(mapper, never()).creditDevelopmentWallet(any(), any(), any(), any());
    }

    @Test
    void routesCloudShareThroughEightGbPoolEvenWhenItsStoredVramIsWrong() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                task("EM-8", "EM", 8, "0.010000", "0.090000"),
                task("VG-48", "VG", 48, "0.400000", "0.600000")));
        when(mapper.developmentTaskDevices()).thenReturn(List.of(
                device(91L, 8103L, "SHARE", "cloud-share", 128)));
        when(mapper.lockDevelopmentTaskDevice(91L, 8103L)).thenReturn(
                device(91L, 8103L, "SHARE", "cloud-share", 128));
        when(mapper.developmentCompletedTaskCount(91L, 8103L)).thenReturn(0L);
        when(mapper.insertDevelopmentRunningTask(any())).thenReturn(1);

        assertThat(bootstrap(mapper).advanceTasks()).isEqualTo(1);

        ArgumentCaptor<DevelopmentRunningTask> task = ArgumentCaptor.forClass(DevelopmentRunningTask.class);
        verify(mapper).insertDevelopmentRunningTask(task.capture());
        assertThat(task.getValue().taskClass()).isEqualTo("EM");
    }

    @Test
    void isolatesAnInvalidDeviceInsteadOfBlockingOtherDeviceProgress() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                task("EM-8", "EM", 8, "0.010000", "0.090000")));
        DevelopmentTaskDevice invalid = new DevelopmentTaskDevice(
                91L, null, "BROKEN", "Broken", "GPU", "phone", "MOBILE", 8,
                NOW.minusDays(1), NOW.minusDays(1), new BigDecimal("1.000000"));
        when(mapper.developmentTaskDevices()).thenReturn(List.of(
                invalid, device(91L, 8104L, "DEVICE", "stellarbox-pro", 12)));
        when(mapper.lockDevelopmentTaskDevice(91L, 8104L)).thenReturn(
                device(91L, 8104L, "DEVICE", "stellarbox-pro", 12));
        when(mapper.developmentCompletedTaskCount(91L, 8104L)).thenReturn(0L);
        when(mapper.insertDevelopmentRunningTask(any())).thenReturn(1);

        assertThat(bootstrap(mapper).advanceTasks()).isEqualTo(1);
        verify(mapper).lockDevelopmentTaskDevice(91L, 8104L);
        verify(mapper, never()).creditDevelopmentWallet(any(), any(), any(), any());
    }

    @Test
    void isolatesASettlementWriteFailureSoAnotherDeviceStillProgresses() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                task("EM-8", "EM", 8, "0.010000", "0.090000")));
        when(mapper.developmentTaskDevices()).thenReturn(List.of(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12),
                device(91L, 8102L, "DEVICE", "stellarbox-pro", 12)));
        when(mapper.lockDevelopmentTaskDevice(91L, 8101L)).thenReturn(
                device(91L, 8101L, "DEVICE", "stellarbox-pro", 12));
        when(mapper.lockDevelopmentTaskDevice(91L, 8102L)).thenReturn(
                device(91L, 8102L, "DEVICE", "stellarbox-pro", 12));
        when(mapper.lockDevelopmentActiveTask(91L, 8101L)).thenReturn(new DevelopmentActiveTask(
                "DEV-TASK-BROKEN", 91L, 8101L, "EM-8", "Embedding", "EM", "BGE-M3",
                "NexGrid Development Workload", new BigDecimal("0.050000"), 5,
                NOW.minusSeconds(6)));
        when(mapper.completeDevelopmentTask(91L, 8101L, "DEV-TASK-BROKEN", NOW)).thenReturn(1);
        when(mapper.insertDevelopmentTaskReceipt(any()))
                .thenThrow(new IllegalStateException("simulated receipt failure"));
        when(mapper.developmentCompletedTaskCount(91L, 8102L)).thenReturn(0L);
        when(mapper.insertDevelopmentRunningTask(any())).thenReturn(1);

        assertThat(bootstrap(mapper).advanceTasks()).isEqualTo(1);

        verify(mapper).lockDevelopmentTaskDevice(91L, 8102L);
        ArgumentCaptor<DevelopmentRunningTask> task = ArgumentCaptor.forClass(DevelopmentRunningTask.class);
        verify(mapper).insertDevelopmentRunningTask(task.capture());
        assertThat(task.getValue().userDeviceId()).isEqualTo(8102L);
    }

    @Test
    void failsClosedBeforeDeviceReadsWhenAnyE2MinimumVramIsInvalid() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                new DevelopmentTaskConfig("BROKEN", "Broken", "EM", "model",
                        new BigDecimal("0.010000"), new BigDecimal("0.090000"), null)));

        assertThat(bootstrap(mapper).advanceTasks()).isZero();

        verify(mapper, never()).developmentTaskDevices();
        verify(mapper, never()).insertDevelopmentRunningTask(any());
    }

    @Test
    void rereadsDeviceAndPolicyAfterLockBeforeChoosingTheTaskPool() {
        DevelopmentHomeSettlementMapper mapper = mock(DevelopmentHomeSettlementMapper.class);
        when(mapper.developmentE3CapacityConfig()).thenReturn(capacityConfig());
        when(mapper.developmentTaskPool()).thenReturn(List.of(
                task("EM-8", "EM", 8, "0.010000", "0.090000"),
                task("VG-48", "VG", 48, "0.400000", "0.600000")));
        when(mapper.developmentTaskDevices()).thenReturn(List.of(
                device(91L, 8105L, "SERVER", "stellarrack-p1", 48)));
        when(mapper.lockDevelopmentTaskDevice(91L, 8105L)).thenReturn(
                device(91L, 8105L, "SHARE", "cloud-share", 0));
        when(mapper.developmentCompletedTaskCount(91L, 8105L)).thenReturn(0L);
        when(mapper.insertDevelopmentRunningTask(any())).thenReturn(1);

        assertThat(bootstrap(mapper).advanceTasks()).isEqualTo(1);

        ArgumentCaptor<DevelopmentRunningTask> task = ArgumentCaptor.forClass(DevelopmentRunningTask.class);
        verify(mapper).insertDevelopmentRunningTask(task.capture());
        assertThat(task.getValue().taskClass()).isEqualTo("EM");
        verify(mapper, org.mockito.Mockito.atLeast(2)).developmentTaskPool();
        verify(mapper, org.mockito.Mockito.atLeast(2)).developmentE3CapacityConfig();
    }

    private DevelopmentHomeSettlementBootstrap bootstrap(DevelopmentHomeSettlementMapper mapper) {
        return new DevelopmentHomeSettlementBootstrap(mapper, CLOCK, "+86", "18708173775", true);
    }

    private DevelopmentTaskConfig task(String id, String taskClass, int minVram,
                                       String minReward, String maxReward) {
        return new DevelopmentTaskConfig(id, taskClass + " task", taskClass, taskClass + " model",
                new BigDecimal(minReward), new BigDecimal(maxReward), minVram);
    }

    private DevelopmentTaskDevice device(Long userId, Long deviceId, String type,
                                         String productCode, int vram) {
        return new DevelopmentTaskDevice(userId, deviceId, "DEV-" + deviceId,
                "Device " + deviceId, "GPU", productCode, type, vram,
                NOW.minusDays(1), NOW.minusDays(1), new BigDecimal("5.000000"));
    }

    private List<DevelopmentHomeSettlementMapper.DevelopmentE3CapacityConfig> capacityConfig() {
        return java.util.Map.ofEntries(
                        java.util.Map.entry("capacityBand1DeltaPct", "-3"),
                        java.util.Map.entry("capacityBand2DeltaPct", "-6"),
                        java.util.Map.entry("capacityBand3DeltaPct", "-23.7"),
                        java.util.Map.entry("stageEarlyEnd", "3"),
                        java.util.Map.entry("stageMidEnd", "8"),
                        java.util.Map.entry("cycleMonths", "13"),
                        java.util.Map.entry("capacityFloorPct", "22"),
                        java.util.Map.entry("capacitySubsidyDays", "30"),
                        java.util.Map.entry("taskLockS1", "30"),
                        java.util.Map.entry("taskLockPro", "150"),
                        java.util.Map.entry("taskLockRack", "480"),
                        java.util.Map.entry("capacityApplyToPhone", "false"),
                        java.util.Map.entry("capacityApplyToCloudShare", "false"),
                        java.util.Map.entry("capacityApplyToPcGpu", "false"),
                        java.util.Map.entry("capacityApplyToS1", "true"),
                        java.util.Map.entry("capacityApplyToPro", "true"),
                        java.util.Map.entry("capacityApplyToProV2", "true"),
                        java.util.Map.entry("capacityApplyToRackP1", "true"),
                        java.util.Map.entry("capacityApplyToRackP2", "true"))
                .entrySet().stream()
                .map(entry -> new DevelopmentHomeSettlementMapper.DevelopmentE3CapacityConfig(
                        entry.getKey(), entry.getValue()))
                .toList();
    }
}
