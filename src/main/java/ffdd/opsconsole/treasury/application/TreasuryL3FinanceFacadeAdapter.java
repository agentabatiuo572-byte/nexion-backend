package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.treasury.facade.TreasuryL3FinanceFacade;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TreasuryL3FinanceFacadeAdapter implements TreasuryL3FinanceFacade {
    private final OpsTreasuryService treasuryService;

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public Map<String, Object> currentL3FinanceSnapshot() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serverAuthoritative", true);
        response.put("coverage", requiredCoverage(treasuryService.coverage()));
        response.put("liabilities", requiredLiabilities(treasuryService.liabilities(true)));
        response.put("maturity7", requiredMaturity(treasuryService.maturityForecast("7d"), "7d", 7));
        response.put("maturity30", requiredMaturity(treasuryService.maturityForecast("30d"), "30d", 30));
        response.put("source", "Treasury canonical read facade");
        return response;
    }

    private Map<String, Object> requiredCoverage(ApiResult<Map<String, Object>> result) {
        Map<String, Object> section = required(result);
        requireKeys(section, Set.of(
                "reserveTotalUsdt", "liabilityTotalUsdt", "coverageRatio", "netExposureUsdt",
                "redLine", "yellowLine", "series", "breaches", "source"));
        if (!(section.get("series") instanceof List<?> series) || series.size() < 2
                || !(section.get("breaches") instanceof List<?>)) {
            invalidSource();
        }
        return section;
    }

    private Map<String, Object> requiredLiabilities(ApiResult<Map<String, Object>> result) {
        Map<String, Object> section = required(result);
        requireKeys(section, Set.of(
                "totalUsdt", "hardLiabilityCategoryCount", "trialShadowIncluded", "breakdown"));
        if (!Integer.valueOf(9).equals(section.get("hardLiabilityCategoryCount"))
                || !Boolean.FALSE.equals(section.get("trialShadowIncluded"))
                || !(section.get("breakdown") instanceof List<?> rows) || rows.size() != 9) {
            invalidSource();
        }
        return section;
    }

    private Map<String, Object> requiredMaturity(
            ApiResult<Map<String, Object>> result, String window, int days) {
        Map<String, Object> section = required(result);
        requireKeys(section, Set.of("window", "daily", "reserveCoverDays"));
        if (!window.equals(section.get("window"))
                || !(section.get("daily") instanceof List<?> rows) || rows.size() != days) {
            invalidSource();
        }
        return section;
    }

    private void requireKeys(Map<String, Object> section, Set<String> keys) {
        if (!section.keySet().containsAll(keys)) invalidSource();
    }

    private Map<String, Object> required(ApiResult<Map<String, Object>> result) {
        if (result == null || result.getCode() != 0 || result.getData() == null) {
            throw new BizException(503, "L3_TREASURY_SOURCE_INVALID");
        }
        return new LinkedHashMap<>(result.getData());
    }

    private void invalidSource() {
        throw new BizException(503, "L3_TREASURY_SOURCE_INVALID");
    }
}
