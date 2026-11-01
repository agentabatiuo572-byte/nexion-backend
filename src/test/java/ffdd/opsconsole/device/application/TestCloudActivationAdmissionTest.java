package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import ffdd.opsconsole.device.compute.v2.ComputeV2Runtime;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TestCloudActivationAdmissionTest {
    @TempDir Path directory;
    final TestCloudRequestMapper mapper=mock(TestCloudRequestMapper.class);
    final org.springframework.mock.env.MockEnvironment env=TestCloudExecutorServiceTest.environment();
    final TestCloudActivationAdmission admission=new TestCloudActivationAdmission(mapper,env,Clock.fixed(Instant.ofEpochSecond(1000),ZoneOffset.UTC));
    Path snapshot(long observed,long expires,boolean allow,int maximum) throws Exception {
        Path file=directory.resolve("capacity.json");
        Files.writeString(file,"{\"version\":1,\"observedAtEpochSeconds\":"+observed+",\"expiresAtEpochSeconds\":"+expires
                +",\"allowNewActivations\":"+allow+",\"maxActiveExecutors\":"+maximum+"}"); return file;
    }
    @Test void missingExpiredFutureAndOversizedValiditySnapshotsRejectNewActivation() throws Exception {
        assertThatThrownBy(()->admission.requireCapacity(directory.resolve("missing"))).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        for(Path file:java.util.List.of(snapshot(950,980,true,6)))
            assertThatThrownBy(()->admission.requireCapacity(file)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        assertThatThrownBy(()->admission.requireCapacity(snapshot(1001,1016,true,6))).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        assertThatThrownBy(()->admission.requireCapacity(snapshot(1000,1031,true,6))).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        assertThatThrownBy(()->admission.requireCapacity(snapshot(1000,1015,false,6))).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
    }
    @Test void currentReservedExecutorsCannotOversubscribeAndProductionIsUnaffected() throws Exception {
        Path file=snapshot(1000,1015,true,6);
        when(mapper.activeResourceExecutors()).thenReturn(5); admission.requireCapacity(file);
        when(mapper.activeResourceExecutors()).thenReturn(6);
        assertThatThrownBy(()->admission.requireCapacity(file)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        env.setProperty(TestCloudExecutorService.PREFIX+"deployment-scope","PRODUCTION");
        admission.requireCapacity(directory.resolve("missing"));
    }
    @Test void aWholeBatchMustFitBeforeAnyNewDeviceIsAdmitted() throws Exception {
        when(mapper.activeResourceExecutors()).thenReturn(5);
        Path file=snapshot(1000,1015,true,6);
        assertThatThrownBy(()->admission.requireCapacity(file,2)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
    }
    @Test void zeroGrowthKeepsExistingResourcesEvenWithoutAnAdmissionSnapshot() {
        admission.requireCapacity(directory.resolve("missing"),0);
        verifyNoInteractions(mapper);
    }
    @Test void replacementUsesTheActualCountedSourceAndNeverSubtractsItTwice() throws Exception {
        when(mapper.activeResourceExecutors()).thenReturn(6);
        Path file=snapshot(1000,1015,true,6);
        when(mapper.resourceExecutorCount(11)).thenReturn(1);
        admission.requireReplacementCapacity(directory.resolve("missing"),11,1);
        // An activated phone or a source without activated_at was never in the global executor count.
        when(mapper.resourceExecutorCount(12)).thenReturn(0);
        assertThatThrownBy(()->admission.requireReplacementCapacity(file,12,1)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
    }
    @Test void quantityValidationAndOverflowCannotTurnNewExecutorsIntoFreeCapacity() throws Exception {
        Path file=snapshot(1000,1015,true,Integer.MAX_VALUE);
        when(mapper.activeResourceExecutors()).thenReturn(Integer.MAX_VALUE);
        assertThatThrownBy(()->admission.requireCapacity(file,1)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        assertThatThrownBy(()->admission.requireCapacity(file,-1)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
    }
    @Test void activationWithNoCanonicalGrowthDoesNotRequireASnapshotOrGlobalCount() {
        when(mapper.activationResourceDelta(11)).thenReturn(0);
        admission.requireActivationCapacity(directory.resolve("missing"),11);
        verify(mapper,never()).activeResourceExecutors();
    }
    @Test void activationThatAddsAPhysicalResourceStillRequiresTheReservedCapacity() throws Exception {
        when(mapper.activationResourceDelta(11)).thenReturn(1); when(mapper.activeResourceExecutors()).thenReturn(6);
        Path file=snapshot(1000,1015,true,6);
        assertThatThrownBy(()->admission.requireActivationCapacity(file,11)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
    }
    TestCloudActivationAdmission v2Admission(String devices,ComputeV2Runtime runtime) {
        env.setProperty("nexion.compute-v2.enabled","true");env.setProperty("nexion.compute-v2.deployment-scope","TEST");
        env.setProperty("nexion.compute-v2.device-ids",devices);
        return new TestCloudActivationAdmission(mapper,env,Clock.fixed(Instant.ofEpochSecond(1000),ZoneOffset.UTC),Optional.ofNullable(runtime));
    }
    Path host(boolean allow,boolean draining,long available,long committed) throws Exception {
        Path file=snapshot(1000,1015,allow,6);long physical=32L*1024*1024*1024;
        Files.writeString(file,Files.readString(file).replace("}",",\"draining\":"+draining+",\"physicalMemoryBytes\":"+physical
                +",\"availableMemoryBytes\":"+available+",\"reserveBytes\":"+((physical+4)/5)
                +",\"committedBytes\":"+committed+",\"outstandingBuildBytes\":0}"));return file;
    }
    @Test void sixThousandEnrolledLogicalActivationsAndRestoresNeedNoPhysicalGrowthOrSnapshot() {
        var guard=v2Admission(LongStream.rangeClosed(1,6000).mapToObj(Long::toString).collect(Collectors.joining(",")),null);
        for(long device=1;device<=6000;device++) {
            guard.requireActivationCapacity(directory.resolve("missing"),device);
            guard.requireRestoreCapacity(directory.resolve("missing"),device);
        }
        verifyNoInteractions(mapper);
    }
    @Test void v1NewAndReplacementWorkExcludeLogicalSlotsButIncludeActualV2Permits() throws Exception {
        var runtime=mock(ComputeV2Runtime.class);when(runtime.isReady()).thenReturn(true);when(runtime.activeExecutionCount()).thenReturn(1);
        var guard=v2Admission("11,12",runtime);when(mapper.activeV1ResourceExecutors(Set.of(11L,12L))).thenReturn(4);
        Path file=host(true,false,10L*1024*1024*1024,24L*1024*1024*1024);guard.requireCapacity(file,1);
        assertThatThrownBy(()->guard.requireCapacity(file,2)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        when(runtime.activeExecutionCount()).thenReturn(2);
        when(mapper.v1ResourceExecutorCount(11,Set.of(11L,12L))).thenReturn(0);
        assertThatThrownBy(()->guard.requireReplacementCapacity(file,11,1)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        when(mapper.v1ResourceExecutorCount(99,Set.of(11L,12L))).thenReturn(1);
        guard.requireReplacementCapacity(directory.resolve("missing"),99,1);
        verify(mapper,never()).activeResourceExecutors();
    }
    @Test void missingV2RuntimeStateCannotBeCountedAsZeroPhysicalUsage() throws Exception {
        var runtime=mock(ComputeV2Runtime.class);var guard=v2Admission("11",runtime);
        assertThatThrownBy(()->guard.requireCapacity(host(true,false,10L*1024*1024*1024,24L*1024*1024*1024),1)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        guard.requireCapacity(directory.resolve("missing"),0);
    }
    @Test void v2ExecutionNeedsFreshAllowedUndrainedHostBudgetAndIncludesOldPhysicalPromises() throws Exception {
        var runtime=mock(ComputeV2Runtime.class);when(runtime.isReady()).thenReturn(true);
        var guard=v2Admission("11",runtime);when(mapper.activeV1ResourceExecutors(Set.of(11L))).thenReturn(4);
        long available=10L*1024*1024*1024,committed=24L*1024*1024*1024;
        Path file=host(true,false,available,committed);
        assertThat(guard.canStartV2Execution(file,0)).isTrue();assertThat(guard.canStartV2Execution(file,1)).isTrue();
        assertThat(guard.canStartV2Execution(file,2)).isFalse();assertThat(guard.canStartV2Execution(file,-1)).isFalse();
        assertThat(guard.canStartV2Execution(host(false,false,available,committed),0)).isFalse();
        assertThat(guard.canStartV2Execution(host(true,true,available,committed),0)).isFalse();
        assertThat(guard.canStartV2Execution(directory.resolve("missing"),0)).isFalse();
        assertThat(guard.canStartV2Execution(snapshot(950,980,true,6),0)).isFalse();
    }
    @Test void hostTwentyPercentReserveIsExactForThirtyTwoGiBAndCannotBeForgedByAllowFlag() throws Exception {
        var guard=v2Admission("11",null);long physical=32L*1024*1024*1024,reserve=(physical+4)/5;
        Path file=host(true,false,reserve,physical-reserve);
        assertThat(guard.readSnapshot(file,true).reserveBytes()).isEqualTo(reserve);
        assertThat(guard.canStartV2Execution(file,0)).isTrue();
        assertThat(guard.canStartV2Execution(host(true,false,reserve-1,physical-reserve),0)).isFalse();
        assertThat(guard.canStartV2Execution(host(true,false,reserve,physical-reserve+1),0)).isFalse();
    }
    @Test void enrollmentCannotExemptV1WhenV2IsDisabledOrOutsideTestScope() throws Exception {
        var guard=v2Admission("11",null);when(mapper.activationResourceDelta(11)).thenReturn(1);when(mapper.activeResourceExecutors()).thenReturn(6);
        env.setProperty("nexion.compute-v2.enabled","false");
        assertThatThrownBy(()->guard.requireActivationCapacity(snapshot(1000,1015,true,6),11)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        env.setProperty("nexion.compute-v2.enabled","true");env.setProperty("nexion.compute-v2.deployment-scope","PRODUCTION");
        assertThatThrownBy(()->guard.requireActivationCapacity(snapshot(1000,1015,true,6),11)).hasMessage("TEST_CLOUD_RESOURCE_CAPACITY_UNAVAILABLE");
        verify(mapper,never()).activeV1ResourceExecutors(any());
    }
}
