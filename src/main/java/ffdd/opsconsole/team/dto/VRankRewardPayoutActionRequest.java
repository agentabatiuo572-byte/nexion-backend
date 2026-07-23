package ffdd.opsconsole.team.dto;

/**
 * F1 V-Rank 派发流水处置请求(Sprint 6 端点第二组:reissue/reverse 共用)。
 *
 * <p>仅承载操作原因,F1-MD4 补发/撤销两端的 resource locator(payoutId)走 path 参数。
 *
 * @param reason   操作原因(必填,长度 >= 6,由 OpsTeamService.requireCommand 校验)
 * @param operator 操作者账号(可选,缺省 resolveOperator 从 SecurityContext 解析 admin id)
 */
public record VRankRewardPayoutActionRequest(
        String reason,
        String operator) {
}
