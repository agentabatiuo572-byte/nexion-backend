package ffdd.opsconsole.device.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import ffdd.opsconsole.device.mapper.TestCloudRequestMapper.Request;
import java.util.*;
import org.junit.jupiter.api.Test;

class TestCloudRequestStatusTest {
    @Test void reviewedTerminationWithoutValidatedProofUsesOnlyCanonicalLeaseExpiry() {
        var f=new TestCloudExecutorServiceTest(); f.setup();
        doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get()).when(f.requests).outside(any());
        doCallRealMethod().when(f.requests).view(any(),anyString(),anyString(),anyString());
        String key="request-status-unit",hash="a".repeat(64);
        var r=new Request("cloud-unit","COMPLETE",TestCloudExecutorService.sha256(key),hash,7,11,"NEX-UNIT-CLOUD","EM-UNIT",
                TestCloudExecutorServiceTest.TASK,"ROLLED_BACK",1,"t".repeat(64),true,0L,false,true,null,TestCloudExecutorServiceTest.NOW);
        when(f.requests.read(f.identity,"COMPLETE",key,hash,TestCloudExecutorServiceTest.TASK)).thenReturn(r);
        assertThat(f.service.requestStatus(f.identity,"COMPLETE",key,hash,TestCloudExecutorServiceTest.TASK).getData())
                .containsEntry("terminationAllowed",false).containsEntry("recoveryAllowed",false);
        var original=TestCloudExecutorServiceTest.task("RUNNING");
        var expired=new ffdd.opsconsole.device.mapper.AppTaskAssignmentMapper.AssignmentRow(original.taskNo(),original.deviceId(),original.taskId(),
                original.taskName(),original.taskClass(),original.modelName(),original.clientName(),original.status(),original.rewardUsdt(),
                original.requiredSeconds(),original.taskLockMinutes(),original.startedAt(),TestCloudExecutorServiceTest.NOW.minusSeconds(1),
                original.completedAt(),original.receiptNo(),original.completionNonce(),original.proofExpiresAt());
        when(f.assignments.assignment(7L,original.taskNo(),"PRODUCTION")).thenReturn(expired);
        assertThat(f.service.requestStatus(f.identity,"COMPLETE",key,hash,original.taskNo()).getData()).containsEntry("terminationAllowed",true);
        verifyNoInteractions(f.settlement,f.audit);
        verify(f.mapper,never()).insert(any(ffdd.opsconsole.device.mapper.TestCloudExecutorMapper.Execution.class)); verify(f.mapper,never()).saveResult(any(),any(),any(),any(),any());
        verify(f.mapper,never()).heartbeat(anyLong(),any(),any(),any());
    }
}
