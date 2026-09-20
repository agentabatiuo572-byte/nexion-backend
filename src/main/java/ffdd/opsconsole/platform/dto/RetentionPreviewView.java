package ffdd.opsconsole.platform.dto;

import java.time.LocalDateTime;

/**
 * 「立即清理」执行前的服务端预览。
 *
 * 缺陷 99 的实质是页面承诺「谁也改不了删不了」却提供一个含义不明的清理按钮:运营无法判断
 * 该动作会删除什么。这个视图让按钮在**操作前**就能展示本次范围 —— 计数与最早日期都由服务端
 * 用与执行完全相同的谓词算出来,前端不再需要(也不允许)自行推断。
 *
 * @param retentionMonths        当前生效的保留期政策(月)
 * @param eligibleRows           本次会被归档+从热表移除的行数
 * @param earliestExpireAt       其中最早一条的 expire_at(无候选时为 null)
 * @param notYetExpiredRows      已带 expire_at 但未到期、本次不会触及的行数
 * @param legacyRowsWithoutExpireAt 政策前历史行(无 expire_at),永不参与清理
 * @param archiveRequired        是否强制先写冷归档并校验摘要后才删热表
 * @param approvalAuthority      执行该动作所需权限(服务端强制校验)
 */
public record RetentionPreviewView(
        int retentionMonths,
        long eligibleRows,
        LocalDateTime earliestExpireAt,
        long notYetExpiredRows,
        long legacyRowsWithoutExpireAt,
        boolean archiveRequired,
        String approvalAuthority) {}
