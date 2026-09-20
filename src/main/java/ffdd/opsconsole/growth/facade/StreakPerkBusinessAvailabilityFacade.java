package ffdd.opsconsole.growth.facade;

/**
 * 连签增益所依赖业务的当前可用性。
 *
 * <p>为什么要有这一层:连签增益的解锁判据此前只有「签到天数」(见
 * {@code nx_streak_power_up.unlock_streak_days})。于是整池质押熔断、Genesis 无
 * ACTIVE 系列时,App 与 PC 仍照常承诺「连签 30 天解锁质押活动入口 / 60 天解锁
 * Genesis 资格入口」——用户投入 30/60 天后撞上一个不可用的页面。</p>
 *
 * <p>业务可用性的权威在各域自己的读模型里(质押整池闸、Genesis 系列就绪),不在这里
 * 重算。本接口只把那些结论汇成连签面需要的三个布尔量,避免 growth 侧与 market 侧
 * 各写一份开关判断。</p>
 *
 * <p>🔴 <b>未知不算停用</b>:读不到状态时一律返回 {@code true}(可用),与 App 侧
 * 「不知道就别说」同源。把一次读取失败说成业务关闭,会让用户以为权益被下掉了。</p>
 */
public interface StreakPerkBusinessAvailabilityFacade {

    /**
     * 质押是否仍对客可售(整池闸开 且 至少一档可售)。
     *
     * <p>返回 {@code true} 也包括「读不到」的情形,见类型注释。</p>
     */
    boolean stakingAvailable();

    /** Genesis 主售是否可用(存在 ACTIVE 系列且市场开放)。 */
    boolean genesisPrimaryAvailable();
}
