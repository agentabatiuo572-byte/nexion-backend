package ffdd.opsconsole.team.domain;

/**
 * 佣金类别的**服务端能力位**——某个类别的收益今天是否真的会派发。
 *
 * <p>为什么必须单一来源:同一事实此前只在 {@code CommissionGuideRuleService} 里以
 * 字面量形式出现,App 的「玩法说明」据此显示「未开放」,而 V-Rank 等级阶梯接口没有
 * 这个字段,于是 /pages/team/rank 把 V3–V12 的「平级 5%」当成已生效权益逐级展示 ——
 * 同一个能力在两页给出相反结论(zentao #79)。
 *
 * <p>判定口径与佣金指南一致:没有部署对应的事件生成器,就不算有能力。仅仅存在
 * F5 的标签或配置项不构成「已派发」的证据。
 */
public final class TeamCommissionCapabilities {

    private TeamCommissionCapabilities() {
    }

    /**
     * 平级奖励是否真的会派发。
     *
     * <p>没有部署 peer 事件生成器,所以是 {@code false}:等级阶梯可以展示「该等级
     * 配置了 5%」,但必须同时标注当前不派发,不能让用户以为升级即可获得。
     */
    public static final boolean PEER_DISPATCHED = false;

    /** 创世奖励同上:没有部署对应生成器。 */
    public static final boolean GENESIS_DISPATCHED = false;
}
