package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.finance.application.FundsSandboxProfileGuard;
import ffdd.opsconsole.market.mapper.AppGenesisMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.canonical.mapper.CanonicalStateMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Authenticated access observations. The caller supplies no count or account;
 * the server validates the subject and evaluator owns all accumulation.
 */
@Service
@RequiredArgsConstructor
public class H3WeeklyParticipationObservationService {
    private final CanonicalStateMapper storefrontMapper;
    private final AppGenesisMapper genesisMapper;
    private final H3WeeklyParticipationEvaluator evaluator;
    private final FundsSandboxProfileGuard fundsSandboxProfileGuard;
    private final Environment environment;

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> observeStorefrontProductDetail(Long userId, String productNo) {
        String normalized = StringUtils.hasText(productNo) ? productNo.trim() : "";
        if (userId == null || userId <= 0 || !normalized.matches("[A-Za-z0-9._:-]{1,64}")) {
            return ApiResult.fail(422, "H3_STOREFRONT_OBSERVATION_INVALID");
        }
        requireProductionStorefrontUser(userId);
        if (storefrontMapper.findVisibleStorefrontProduct(normalized) == null) {
            return ApiResult.fail(404, "PRODUCT_NOT_AVAILABLE");
        }
        evaluator.recordStorefrontProductDetail(userId, normalized);
        return ApiResult.ok(Map.of("accepted", true));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> observeGenesisSecondaryMarket(Long userId) {
        requireProductionGenesisUser(userId);
        evaluator.recordGenesisSecondaryMarketView(userId);
        return ApiResult.ok(Map.of("accepted", true));
    }

    private void requireProductionStorefrontUser(Long userId) {
        if (!fundsSandboxProfileGuard.isStrictProductionRuntime()) {
            throw new IllegalStateException("H3_STOREFRONT_PRODUCTION_RUNTIME_REQUIRED");
        }
        Integer sandbox = storefrontMapper.activeUserEnvironment(userId);
        if (sandbox == null) throw new IllegalArgumentException("USER_NOT_FOUND");
        if (sandbox != 0) throw new IllegalArgumentException("H3_STOREFRONT_PRODUCTION_USER_REQUIRED");
    }

    private void requireProductionGenesisUser(Long userId) {
        String[] profiles = environment == null ? new String[0] : environment.getActiveProfiles();
        if (FundsSandboxProfileGuard.isStrictTestProfile(profiles)) {
            throw new IllegalStateException("H3_GENESIS_PRODUCTION_RUNTIME_REQUIRED");
        }
        boolean production = FundsSandboxProfileGuard.isStrictDevelopmentProfile(profiles)
                || profiles == null || profiles.length == 0
                || (profiles.length == 1 && "prod".equals(profiles[0]));
        if (!production) throw new IllegalStateException("H3_GENESIS_PRODUCTION_RUNTIME_REQUIRED");
        if (!Integer.valueOf(0).equals(genesisMapper.userSandbox(userId))) {
            throw new IllegalArgumentException("H3_GENESIS_PRODUCTION_USER_REQUIRED");
        }
    }
}
