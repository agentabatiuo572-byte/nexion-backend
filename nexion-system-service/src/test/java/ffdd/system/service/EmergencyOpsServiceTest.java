package ffdd.system.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import ffdd.common.exception.BizException;
import ffdd.system.domain.EmergencySopStep;
import ffdd.system.domain.EmergencyTamperGate;
import ffdd.system.dto.EmergencySopStatusRequest;
import ffdd.system.dto.EmergencySopStepResponse;
import ffdd.system.dto.EmergencyTamperGateResponse;
import ffdd.system.dto.EmergencyTamperReviewRequest;
import ffdd.system.mapper.EmergencySopStepMapper;
import ffdd.system.mapper.EmergencyTamperGateMapper;
import ffdd.system.service.impl.EmergencyOpsServiceImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmergencyOpsServiceTest {
    private final EmergencyTamperGateMapper tamperGateMapper = mock(EmergencyTamperGateMapper.class);
    private final EmergencySopStepMapper sopStepMapper = mock(EmergencySopStepMapper.class);
    private final EmergencyOpsServiceImpl service = new EmergencyOpsServiceImpl(tamperGateMapper, sopStepMapper);

    @Test
    void listTamperGatesBootstrapsCanonicalBackendRows() {
        when(tamperGateMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        doAnswer(invocation -> {
                    EmergencyTamperGate row = invocation.getArgument(0);
                    row.setId((long) (100 + row.getGateKey().length()));
                    return 1;
                })
                .when(tamperGateMapper)
                .insert(any(EmergencyTamperGate.class));

        List<EmergencyTamperGateResponse> rows = service.listTamperGates();

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(EmergencyTamperGateResponse::getGateKey)
                .containsExactly("client_version", "ab_group", "local_balance", "risk_tamper_detected");
        assertThat(rows).extracting(EmergencyTamperGateResponse::getEventCount24h)
                .containsExactly(0, 3, 12, 2);
        ArgumentCaptor<EmergencyTamperGate> captor = ArgumentCaptor.forClass(EmergencyTamperGate.class);
        verify(tamperGateMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(row -> row.getIsDeleted() == 0 && row.getStatus() == 1);
    }

    @Test
    void reviewTamperGateUsesWhitelistAndPersistsReviewer() {
        when(tamperGateMapper.selectOne(any(Wrapper.class))).thenReturn(tamper("local_balance", "本地余额改写拦截", 12));
        EmergencyTamperReviewRequest request = new EmergencyTamperReviewRequest();
        request.setVerdict("false_positive");
        request.setOperator("risk-admin");
        request.setReason("复核后确认客户端 SDK 误报");

        EmergencyTamperGateResponse response = service.reviewTamperGate("local_balance", request);

        ArgumentCaptor<EmergencyTamperGate> captor = ArgumentCaptor.forClass(EmergencyTamperGate.class);
        verify(tamperGateMapper).updateById(captor.capture());
        assertThat(captor.getValue().getVerdict()).isEqualTo("FALSE_POSITIVE");
        assertThat(captor.getValue().getReviewedBy()).isEqualTo("risk-admin");
        assertThat(response.getVerdict()).isEqualTo("FALSE_POSITIVE");
    }

    @Test
    void rejectsUnknownTamperGateAndVerdict() {
        EmergencyTamperReviewRequest request = new EmergencyTamperReviewRequest();
        request.setVerdict("ignored");
        request.setOperator("risk-admin");
        request.setReason("bad");

        assertThatThrownBy(() -> service.reviewTamperGate("unknown", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not found");

        assertThatThrownBy(() -> service.reviewTamperGate("local_balance", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Invalid tamper verdict");
    }

    @Test
    void listSopStepsBootstrapsSixOrderedSteps() {
        when(sopStepMapper.selectList(any(Wrapper.class))).thenReturn(new ArrayList<>());
        doAnswer(invocation -> {
                    EmergencySopStep row = invocation.getArgument(0);
                    row.setId(row.getStepOrder().longValue());
                    return 1;
                })
                .when(sopStepMapper)
                .insert(any(EmergencySopStep.class));

        List<EmergencySopStepResponse> rows = service.listSopSteps();

        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(EmergencySopStepResponse::getSopId)
                .containsExactly("step.1", "step.2", "step.3", "step.4", "step.5", "step.6");
        assertThat(rows).allMatch(row -> "PENDING".equals(row.getStatus()));
    }

    @Test
    void updateSopStepStatusValidatesStatusAndOperator() {
        when(sopStepMapper.selectOne(any(Wrapper.class))).thenReturn(sop("step.3", 3));
        EmergencySopStatusRequest request = new EmergencySopStatusRequest();
        request.setStatus("done");
        request.setOperator("ops-admin");
        request.setReason("监管点名流程完成");

        EmergencySopStepResponse response = service.updateSopStepStatus("step.3", request);

        ArgumentCaptor<EmergencySopStep> captor = ArgumentCaptor.forClass(EmergencySopStep.class);
        verify(sopStepMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DONE");
        assertThat(captor.getValue().getOperator()).isEqualTo("ops-admin");
        assertThat(response.getStatus()).isEqualTo("DONE");
    }

    @Test
    void rejectsUnknownSopStepAndStatus() {
        EmergencySopStatusRequest request = new EmergencySopStatusRequest();
        request.setStatus("paused");
        request.setOperator("ops-admin");
        request.setReason("bad");

        assertThatThrownBy(() -> service.updateSopStepStatus("step.99", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("not found");

        assertThatThrownBy(() -> service.updateSopStepStatus("step.1", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Invalid SOP status");
    }

    private EmergencyTamperGate tamper(String gateKey, String gateName, int count) {
        EmergencyTamperGate row = new EmergencyTamperGate();
        row.setId(1L);
        row.setGateKey(gateKey);
        row.setGateName(gateName);
        row.setEventCount24h(count);
        row.setStatus(1);
        row.setIsDeleted(0);
        return row;
    }

    private EmergencySopStep sop(String sopId, int order) {
        EmergencySopStep row = new EmergencySopStep();
        row.setId((long) order);
        row.setSopId(sopId);
        row.setStepOrder(order);
        row.setStepTitle("step " + order);
        row.setStatus("PENDING");
        row.setIsDeleted(0);
        return row;
    }
}
