package ffdd.opsconsole.team.dto;

/**
 * F1-MD1 V-Rank 手动晋升/回滚请求(Sprint 5 端点 2 入参)。
 *
 * <ul>
 *   <li>{@code targetV} — 目标阶代码(V0-V12),必填。</li>
 *   <li>{@code direction} — 操作方向:{@code promote}(晋升,向下兼容越级)/
 *       {@code rollback}(回滚)。必须与 targetV vs currentV 的大小关系一致。</li>
 *   <li>{@code reason} — 操作原因(不少于 6 字,requireCommand 强校验)。</li>
 *   <li>{@code operator} — 操作人 admin 账号(可选,缺省置 "MANUAL")。</li>
 * </ul>
 */
public record VRankOverrideRequest(
        String targetV,
        String direction,
        String reason,
        String operator) {
}
