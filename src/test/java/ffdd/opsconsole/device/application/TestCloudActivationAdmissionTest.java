package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper;
import java.nio.file.*;
import java.time.*;
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
}
