package ffdd.opsconsole.risk.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.risk.mapper.RiskOpsMapper;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import org.junit.jupiter.api.Test;

class MybatisRiskOpsRepositoryTest {
    private final RiskOpsMapper mapper = mock(RiskOpsMapper.class);

    @Test
    void ensureRiskSchemaDoesNotSeedDataWhenReadTimeSeedsAreDisabled() {
        MybatisRiskOpsRepository repository = new MybatisRiskOpsRepository(
                mapper,
                new OpsReadTimeSeedPolicy(false));

        repository.ensureRiskSchema();

        verify(mapper).createRiskDecisionTable();
        verify(mapper).createKycAlertTable();
        verify(mapper, never()).countRiskCases();
        verify(mapper, never()).insertSeedRiskDecision(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
