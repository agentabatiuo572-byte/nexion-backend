package ffdd.opsconsole.growth.dto;

/**
 * H4 抽奖转盘护栏(wheelGuards)新建请求,业务表 nx_growth_wheel_guard(目录数据)。
 * guardKey/guardLabel 为目录项;guardValue 为默认值(运行时数值仍走配置项 growth.wheel.guard.*)。
 */
public record GrowthWheelGuardRequest(
        String guardKey,
        String guardLabel,
        String guardValue,
        String note,
        String reason,
        String operator) {
}
