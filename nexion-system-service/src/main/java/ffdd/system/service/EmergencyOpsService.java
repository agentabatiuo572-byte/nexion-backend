package ffdd.system.service;

import ffdd.system.dto.EmergencySopStatusRequest;
import ffdd.system.dto.EmergencySopStepResponse;
import ffdd.system.dto.EmergencyTamperGateResponse;
import ffdd.system.dto.EmergencyTamperReviewRequest;
import java.util.List;

public interface EmergencyOpsService {
    List<EmergencyTamperGateResponse> listTamperGates();

    EmergencyTamperGateResponse reviewTamperGate(String gateKey, EmergencyTamperReviewRequest request);

    List<EmergencySopStepResponse> listSopSteps();

    EmergencySopStepResponse updateSopStepStatus(String sopId, EmergencySopStatusRequest request);
}
