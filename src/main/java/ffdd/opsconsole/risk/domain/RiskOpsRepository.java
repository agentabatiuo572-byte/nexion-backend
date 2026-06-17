package ffdd.opsconsole.risk.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RiskOpsRepository {
    Map<String, Object> overview();

    List<RiskCaseView> search(Long userId, String status, String decision, int limit);

    Optional<RiskCaseView> findByCaseNo(String caseNo);

    void updateDecision(String caseNo, String decision, String reason, String operator);

    void recordSignal(String signalNo, Long userId, String signalType, String severity, String evidence, String operator);
}
