package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.facade.StreakPerkBusinessAvailabilityFacade;
import ffdd.opsconsole.market.application.AppStakingService;
import ffdd.opsconsole.market.application.GenesisCatalogService;
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
 *   <li>质押 —— {@link AppStakingService#pools()} 的 {@code pools[]}
 *       ({@code enabled}/{@code killed}/{@code status})。这与 App 质押页方案行、以及
 *       App 任务面 {@code useQuestTargetAvailability} 读的是**同一份**公开目录
 *       ({@code GET /api/config/staking/pools}),判定口径也与 App 的
 *       {@code canOpenStakingPool}({@code enabled && !killed && status==ACTIVE})逐字一致。</li>
 *   <li>Genesis —— {@link GenesisCatalogService#publicState()} 的
 *       {@code catalogAvailable} 与 {@code tradeAvailable}。</li>
 * </ul>
 *
 * <p>🔴 <b>读不到就返回 true(可用)</b>:与 App 侧「不知道就别说」同一原则。把一次读取
 * 失败说成业务关闭,会让用户以为已获得的权益被下掉了 —— 而这个方向的错误代价更大。
 * 注意「读不到」指**抛异常或响应不可用**;成功读到一份「没有任何一档在售」的目录是
 * 知识而不是未知,那种情况必须返回 false。</p>
 *
 * <p>🔴 <b>历史教训(zentao #195)</b>:本类原先读 {@code OpsNexMarketService.overview()}
 * 的 {@code stats.stakingGateOn} 与 {@code pools[].sellable}。那两个键属于**另一个方法**
 * ({@code OpsNexMarketService.stakingOverview()} / {@code stakingPoolRow()}),而
 * {@code overview()} 是 **G3 NEX 价格市场**总览,响应只有
 * domain/currentPrice/frames/controls/coverage 等键 —— 没有 {@code stats},也没有
 * {@code pools}。两个 {@code instanceof} 守卫于是都不成立、各自走「当作开着」的默认分支,
 * 方法**恒返回 true**。后果:质押四档全部 STOPPED 时,任务面(读公开目录)如实标
 * 「业务暂停」,连签增益(读这个恒真值)却继续承诺「30 天 质押活动入口」并显示解锁倒计时,
 * 同一事实在两端各说一套。修法不是加判据,而是把读数换成**同一个**权威来源。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreakPerkBusinessAvailabilityAdapter implements StreakPerkBusinessAvailabilityFacade {

    private final AppStakingService appStakingService;
    private final GenesisCatalogService genesisCatalogService;

    @Override
    public boolean stakingAvailable() {
        try {
            ApiResult<Map<String, Object>> result = appStakingService.pools();
            if (result == null || result.getCode() != 0 || result.getData() == null) return true;
            Object poolsValue = result.getData().get("pools");
            if (!(poolsValue instanceof List<?> pools)) return true;
            // 目录读到了:至少一档「可售」才算对客可用。空目录 = 没有可买的东西 = 关闭,
            // 与 App 侧对空目录的判定一致(那边 !pools.some(canOpen) 也为 closed)。
            // 判据与 canOpenStakingPool 同源:enabled 已含 !killed && 全局闸,
            // 这里再显式要求 !killed 与 status=ACTIVE,避免 poolView 之外的第二套口径。
            return pools.stream().anyMatch(pool -> pool instanceof Map<?, ?> row
                    && Boolean.TRUE.equals(row.get("enabled"))
                    && !Boolean.TRUE.equals(row.get("killed"))
                    && "ACTIVE".equals(row.get("status")));
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
