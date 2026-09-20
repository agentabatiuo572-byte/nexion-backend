package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade;
import ffdd.opsconsole.market.application.GenesisCatalogService;
import ffdd.opsconsole.market.application.OpsNexMarketService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 连签增益业务可用性的唯一实现。
 *
 * <p>两个判据都取自各域**自己的**权威读模型,不在这里重算开关:</p>
 * <ul>
 *   <li>质押 —— {@link OpsNexMarketService#overview()} 的 {@code stats.stakingGateOn}
 *       与 {@code pools[].sellable}:整池闸开且至少一档可售才算「对客可售」。</li>
 *   <li>Genesis —— {@link GenesisCatalogService#publicState()} 的
 *       {@code catalogAvailable} 与 {@code tradeAvailable}。</li>
 * </ul>
 *
 * <p>🔴 <b>读不到就返回 true(可用)</b>:与 App 侧「不知道就别说」同一原则。把一次读取
 * 失败说成业务关闭,会让用户以为已获得的权益被下掉了 —— 而这个方向的错误代价更大。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreakPerkBusinessAvailabilityAdapter implements StreakPerkBusinessAvailabilityFacade {

    private final OpsNexMarketService marketService;
    private final GenesisCatalogService genesisCatalogService;

    @Override
    public boolean stakingAvailable() {
        try {
            ApiResult<Map<String, Object>> result = marketService.overview();
            if (result == null || result.getCode() != 0 || result.getData() == null) return true;
            Map<String, Object> data = result.getData();
            Object statsValue = data.get("stats");
            boolean gateOn = true;
            if (statsValue instanceof Map<?, ?> stats) {
                Object gate = stats.get("stakingGateOn");
                gateOn = !(gate instanceof Boolean value) || value;
            }
            if (!gateOn) return false;
            // 整池闸开着也可能四档全部停售 —— 那同样是「用户点进去没有可买的东西」。
            Object poolsValue = data.get("pools");
            if (!(poolsValue instanceof List<?> pools) || pools.isEmpty()) return true;
            return pools.stream().anyMatch(pool -> pool instanceof Map<?, ?> row
                    && Boolean.TRUE.equals(row.get("sellable")));
        } catch (RuntimeException ex) {
            log.warn("Streak perk staking availability read failed; treating as available", ex);
            return true;
        }
    }

    @Override
    public boolean genesisPrimaryAvailable() {
        try {
            Map<String, Object> state = genesisCatalogService.publicState();
            if (state == null) return true;
            // 两个都要:有 ACTIVE 系列(可认购) 且 市场开放(可下单)。
            return Boolean.TRUE.equals(state.get("catalogAvailable"))
                    && Boolean.TRUE.equals(state.get("tradeAvailable"));
        } catch (RuntimeException ex) {
            log.warn("Streak perk genesis availability read failed; treating as available", ex);
            return true;
        }
    }
}
