package ffdd.opsconsole.content.dto;

import ffdd.opsconsole.content.domain.CopyAudienceTarget;

/**
 * 新建文案位请求 —— 标准集:建位即带首版(version/zh/en),i18nKey 软关联(默认=copyKey)。
 * 与 CopyVersionPublishRequest 同源字段(version/surface/audience/trafficSplit/versionNote/zh/en),
 * 额外带 copyKey/description/i18nKey 用于初始化文案位。
 */
public record CopyCreateRequest(
        String copyKey,
        String description,
        String surface,
        String i18nKey,
        String version,
        String audience,
        CopyAudienceTarget audienceTarget,
        String trafficSplit,
        String versionNote,
        String zh,
        String en,
        String vi,
        String copyPosition,
        String operator,
        String reason) {

    public CopyCreateRequest(
            String copyKey, String description, String surface, String i18nKey, String version,
            String audience, String trafficSplit, String versionNote, String zh, String en, String vi,
            String copyPosition, String operator, String reason) {
        this(copyKey, description, surface, i18nKey, version, audience, null, trafficSplit, versionNote,
                zh, en, vi, copyPosition, operator, reason);
    }
}
