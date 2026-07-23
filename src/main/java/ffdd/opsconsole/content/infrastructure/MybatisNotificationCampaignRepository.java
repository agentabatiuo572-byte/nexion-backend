package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationAudienceTarget;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
import ffdd.opsconsole.content.domain.AppNotificationPage;
import ffdd.opsconsole.content.domain.AppNotificationView;
import ffdd.opsconsole.content.domain.NotificationActionReceipt;
import ffdd.opsconsole.content.domain.NotificationEventFact;
import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import ffdd.opsconsole.content.mapper.NotificationCapRuleMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class MybatisNotificationCampaignRepository implements NotificationCampaignRepository {
    private final NotificationCampaignMapper campaignMapper;
    private final NotificationCapRuleMapper capRuleMapper;

    @Override
    public void ensureSeedData(LocalDateTime now) {
        // Business rows must come from MySQL writes, not read-time demo seeds.
    }

    @Override
    public List<NotificationCampaignRow> listCampaigns() {
        return campaignMapper.selectList(new LambdaQueryWrapper<NotificationCampaignEntity>()
                        .eq(NotificationCampaignEntity::getIsDeleted, 0)
                        .orderByDesc(NotificationCampaignEntity::getCreatedAt)
                        .orderByDesc(NotificationCampaignEntity::getId))
                .stream()
                .map(this::toRow)
                .toList();
    }

    @Override
    public Optional<NotificationCampaignRow> findCampaign(String campaignNo) {
        return Optional.ofNullable(findEntity(campaignNo)).map(this::toRow);
    }

    @Override
    public NotificationCampaignRow createCampaign(String campaignNo, NotificationCampaignCreateRequest request, long estimatedAudience, LocalDateTime now) {
        NotificationCampaignEntity entity = new NotificationCampaignEntity();
        entity.setCampaignNo(campaignNo);
        entity.setName(request.name().trim());
        entity.setKind(request.kind().trim().toLowerCase(Locale.ROOT));
        entity.setTier(request.tier().trim().toLowerCase(Locale.ROOT));
        entity.setAudience(encodeAudience(request.audienceTarget()));
        entity.setReachLabel(String.valueOf(estimatedAudience));
        entity.setStatus("DRAFT");
        entity.setScheduleText("-");
        entity.setSentLabel("-");
        entity.setReadLabel("-");
        entity.setBodyEn(notificationBody(request.titleEn(), request.bodyEn()));
        entity.setBodyZh(notificationBody(request.titleZh(), request.bodyZh()));
        entity.setBodyVi(notificationBody(request.titleVi(), request.bodyVi()));
        entity.setSwipeTo("-");
        entity.setCtaLabel(text(request.ctaLabel()));
        entity.setCtaHref(text(request.ctaHref()));
        entity.setBudgetUsd(request.budget());
        entity.setCreatedBy(operator(request.operator()));
        entity.setLastOperator(operator(request.operator()));
        entity.setRevision(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        campaignMapper.insert(entity);
        return findCampaign(campaignNo).orElse(toRow(entity));
    }

    @Override
    public boolean updateDraft(
            String campaignNo,
            NotificationCampaignDraftRequest request,
            long estimatedAudience,
            long expectedRevision,
            LocalDateTime now) {
        return campaignMapper.updateDraftIfRevision(
                campaignNo,
                request.name().trim(),
                request.kind().trim().toLowerCase(Locale.ROOT),
                request.tier().trim().toLowerCase(Locale.ROOT),
                encodeAudience(request.audienceTarget()),
                String.valueOf(estimatedAudience),
                notificationBody(request.titleEn(), request.bodyEn()),
                notificationBody(request.titleZh(), request.bodyZh()),
                notificationBody(request.titleVi(), request.bodyVi()),
                text(request.ctaLabel()),
                text(request.ctaHref()),
                request.budget(),
                operator(request.operator()),
                expectedRevision,
                now) == 1;
    }

    @Override
    public long estimateAudience(NotificationAudienceTarget target, String currentPhase, LocalDateTime now) {
        if (!phaseMatches(target, currentPhase)) {
            return 0L;
        }
        return campaignMapper.countAudience(target.language(), target.registrationDaysMin(), now);
    }

    @Override
    public boolean deleteDraft(String campaignNo, long expectedRevision, LocalDateTime now) {
        return campaignMapper.softDeleteDraft(campaignNo, expectedRevision, now) > 0;
    }

    @Override
    public boolean scheduleDraft(String campaignNo, String schedule, String operator, long expectedRevision, LocalDateTime now) {
        return campaignMapper.scheduleDraftIfRevision(
                campaignNo, schedule, operator(operator), expectedRevision, now) == 1;
    }

    @Override
    public void updateStatus(String campaignNo, String status, String schedule, String operator, LocalDateTime now) {
        NotificationCampaignEntity entity = findEntity(campaignNo);
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        if (StringUtils.hasText(schedule)) {
            entity.setScheduleText(schedule.trim());
        }
        entity.setLastOperator(operator(operator));
        entity.setRevision(revision(entity) + 1);
        entity.setUpdatedAt(now);
        campaignMapper.updateById(entity);
    }

    @Override
    public int dispatchCampaignNotification(String campaignNo, String bizNo, String currentPhase, String trigger, String operator, LocalDateTime now) {
        NotificationCampaignEntity entity = findEntity(campaignNo);
        if (entity == null || "CANCELLED".equalsIgnoreCase(entity.getStatus())) {
            return 0;
        }
        NotificationAudienceTarget target = decodeAudience(entity.getAudience());
        if (!phaseMatches(target, currentPhase)) {
            return 0;
        }
        String bodyVi = localizedBody(entity.getBodyVi(), entity.getBodyZh());
        String bodyEn = localizedBody(entity.getBodyEn(), bodyVi);
        int inserted = campaignMapper.insertCampaignNotifications(
                bizNo,
                entity.getKind(),
                entity.getTier(),
                target.language(),
                target.registrationDaysMin(),
                localizedTitle(entity.getBodyZh(), entity.getName()),
                localizedContent(entity.getBodyZh()),
                localizedTitle(bodyVi, entity.getName()),
                localizedContent(bodyVi),
                localizedTitle(bodyEn, ""),
                localizedContent(bodyEn),
                text(entity.getCtaLabel()),
                text(entity.getCtaHref()),
                now);
        campaignMapper.markCampaignNotificationsDelivered(bizNo, now);
        return inserted;
    }

    @Override
    public int countNotificationsByBizNo(String bizNo) {
        return campaignMapper.countNotificationsByBizNo(bizNo);
    }

    @Override
    public List<NotificationEventFact> listNotificationEventFactsByBizNo(
            String bizNo, String currentPhase, LocalDateTime now) {
        return campaignMapper.selectNotificationEventFactsByBizNo(bizNo, currentPhase, now);
    }

    @Override
    public List<String> listDueScheduledCampaignNos(LocalDateTime now, int limit) {
        return campaignMapper.selectDueScheduledCampaignNos(now, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public boolean claimScheduled(String campaignNo, LocalDateTime now) {
        return campaignMapper.claimScheduled(campaignNo, now) > 0;
    }

    @Override
    public boolean claimForImmediateDispatch(String campaignNo, long expectedRevision, LocalDateTime now) {
        return campaignMapper.claimForImmediateDispatch(campaignNo, expectedRevision, now) > 0;
    }

    @Override
    public boolean cancelScheduled(String campaignNo, String operator, long expectedRevision, LocalDateTime now) {
        return campaignMapper.cancelScheduled(campaignNo, operator(operator), expectedRevision, now) > 0;
    }

    @Override
    public void completeDispatch(String campaignNo, String status, int sentCount, String schedule, String operator, LocalDateTime now) {
        NotificationCampaignEntity entity = findEntity(campaignNo);
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        entity.setSentLabel(String.valueOf(Math.max(0, sentCount)));
        entity.setReachLabel(String.valueOf(Math.max(0, sentCount)));
        entity.setScheduleText(schedule);
        entity.setLastOperator(operator(operator));
        entity.setRevision(revision(entity) + 1);
        entity.setUpdatedAt(now);
        campaignMapper.updateById(entity);
    }

    @Override
    public int recoverStaleSending(LocalDateTime staleBefore, LocalDateTime now) {
        return campaignMapper.recoverStaleSending(staleBefore, now);
    }

    @Override
    public void applyRetention(LocalDateTime now) {
        applyRetention(null, now);
    }

    @Override
    public void applyRetentionForUser(Long userId) {
        applyRetention(userId, LocalDateTime.now());
    }

    @Override
    public AppNotificationPage pageUserNotifications(Long userId, Long cursorId, String priority, int limit) {
        List<AppNotificationView> rows = campaignMapper.selectUserNotifications(
                userId, cursorId, priority, Math.max(1, Math.min(limit, 100)) + 1);
        boolean hasMore = rows.size() > limit;
        List<AppNotificationView> items = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore && !items.isEmpty()
                ? String.valueOf(items.get(items.size() - 1).id())
                : null;
        return new AppNotificationPage(List.copyOf(items), nextCursor, campaignMapper.countUnreadForUser(userId));
    }

    @Override
    public boolean markNotificationRead(Long userId, Long notificationId) {
        return campaignMapper.markUserNotificationRead(userId, notificationId) > 0;
    }

    @Override
    public Optional<NotificationEventFact> lockNotificationEventFact(Long userId, Long notificationId) {
        return Optional.ofNullable(campaignMapper.lockNotificationEventFact(userId, notificationId));
    }

    @Override
    public List<NotificationEventFact> lockUnreadNotificationEventFacts(Long userId) {
        return campaignMapper.lockUnreadNotificationEventFacts(userId);
    }

    @Override
    public Optional<NotificationActionReceipt> findNotificationActionReceipt(String idempotencyKey) {
        return Optional.ofNullable(campaignMapper.findNotificationActionReceipt(idempotencyKey));
    }

    @Override
    public boolean recordNotificationAction(
            Long userId, Long notificationId, String action, String route, String idempotencyKey) {
        return campaignMapper.insertNotificationActionReceipt(
                userId, notificationId, action, route, idempotencyKey) > 0;
    }

    @Override
    public int markAllNotificationsRead(Long userId) {
        return campaignMapper.markAllUserNotificationsRead(userId);
    }

    @Override
    public int clearReadNotifications(Long userId) {
        return campaignMapper.clearReadUserNotifications(userId);
    }

    @Override
    public List<NotificationCapRuleView> listCapRules() {
        return capRuleMapper.selectList(new LambdaQueryWrapper<NotificationCapRuleEntity>()
                        .eq(NotificationCapRuleEntity::getIsDeleted, 0)
                        .eq(NotificationCapRuleEntity::getStatus, 1)
                        .orderByAsc(NotificationCapRuleEntity::getSortOrder)
                        .orderByAsc(NotificationCapRuleEntity::getId))
                .stream()
                .map(this::toCapView)
                .toList();
    }

    @Override
    public Optional<NotificationCapRuleView> findCapRule(String tier) {
        return Optional.ofNullable(findCapEntity(tier)).map(this::toCapView);
    }

    @Override
    public void updateCapRule(String tier, String cap, String operator, LocalDateTime now) {
        NotificationCapRuleEntity entity = findCapEntity(tier);
        if (entity == null) {
            return;
        }
        entity.setCapLabel(cap.trim());
        entity.setLastOperator(operator(operator));
        entity.setUpdatedAt(now);
        capRuleMapper.updateById(entity);
    }

    private NotificationCampaignEntity findEntity(String campaignNo) {
        return campaignMapper.selectOne(new LambdaQueryWrapper<NotificationCampaignEntity>()
                .eq(NotificationCampaignEntity::getCampaignNo, campaignNo)
                .eq(NotificationCampaignEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private NotificationCapRuleEntity findCapEntity(String tier) {
        return capRuleMapper.selectOne(new LambdaQueryWrapper<NotificationCapRuleEntity>()
                .eq(NotificationCapRuleEntity::getTier, tier)
                .eq(NotificationCapRuleEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private NotificationCampaignRow toRow(NotificationCampaignEntity entity) {
        NotificationAudienceTarget target = decodeAudience(entity.getAudience());
        return new NotificationCampaignRow(
                entity.getCampaignNo(),
                entity.getName(),
                entity.getKind(),
                entity.getTier(),
                audienceLabel(target),
                entity.getReachLabel(),
                toViewStatus(entity.getStatus()),
                entity.getScheduleText(),
                entity.getSentLabel(),
                entity.getReadLabel(),
                entity.getBodyEn(),
                entity.getBodyZh(),
                StringUtils.hasText(entity.getBodyVi()) ? entity.getBodyVi() : entity.getBodyZh(),
                entity.getSwipeTo(),
                entity.getBudgetUsd(),
                target,
                text(entity.getCtaLabel()),
                text(entity.getCtaHref()),
                revision(entity));
    }

    private NotificationCapRuleView toCapView(NotificationCapRuleEntity entity) {
        return new NotificationCapRuleView(
                entity.getTier(),
                entity.getCapLabel(),
                entity.getPolicy(),
                entity.getLocked() != null && entity.getLocked() == 1);
    }

    private String toViewStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toLowerCase(Locale.ROOT).replace('_', '-') : "draft";
    }

    private String notificationBody(String title, String content) {
        return title.trim() + "\n" + content.trim();
    }

    private String encodeAudience(NotificationAudienceTarget target) {
        return String.join("|", target.phaseMin(), target.phaseMax(), target.language(), String.valueOf(target.registrationDaysMin()));
    }

    private NotificationAudienceTarget decodeAudience(String value) {
        if (StringUtils.hasText(value)) {
            String[] parts = value.split("\\|", -1);
            if (parts.length == 4) {
                try {
                    return new NotificationAudienceTarget(parts[0], parts[1], parts[2], Integer.parseInt(parts[3]));
                } catch (NumberFormatException ignored) {
                    // Legacy labels fall back to the full-audience structure below.
                }
            }
        }
        return new NotificationAudienceTarget("P1", "P6", "all", 0);
    }

    private String audienceLabel(NotificationAudienceTarget target) {
        String language = switch (target.language()) {
            case "zh" -> "中文";
            case "vi" -> "越南语";
            case "en" -> "英文";
            default -> "全语言";
        };
        return "%s～%s · %s · 注册大于 %d 天".formatted(
                target.phaseMin(), target.phaseMax(), language, target.registrationDaysMin());
    }

    private boolean phaseMatches(NotificationAudienceTarget target, String currentPhase) {
        int current = phaseIndex(currentPhase);
        return current >= phaseIndex(target.phaseMin()) && current <= phaseIndex(target.phaseMax());
    }

    private int phaseIndex(String phase) {
        if (!StringUtils.hasText(phase) || !phase.trim().toUpperCase(Locale.ROOT).matches("P[1-6]")) {
            return -1;
        }
        return phase.trim().charAt(1) - '1';
    }

    private String localizedBody(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String localizedTitle(String combined, String fallback) {
        if (!StringUtils.hasText(combined)) {
            return fallback;
        }
        int newline = combined.indexOf('\n');
        String title = newline < 0 ? combined : combined.substring(0, newline);
        return StringUtils.hasText(title) ? title.trim() : fallback;
    }

    private String localizedContent(String combined) {
        if (!StringUtils.hasText(combined)) {
            return "";
        }
        int newline = combined.indexOf('\n');
        return newline < 0 ? combined.trim() : combined.substring(newline + 1).trim();
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private long revision(NotificationCampaignEntity entity) {
        return entity.getRevision() == null ? 0L : entity.getRevision();
    }

    private void applyRetention(Long userId, LocalDateTime now) {
        for (NotificationCapRuleView rule : listCapRules()) {
            if (rule.locked() || "critical".equalsIgnoreCase(rule.tier())) {
                continue;
            }
            int cap = numericCap(rule.cap(), defaultCap(rule.tier()));
            campaignMapper.pruneNotificationsOverCap(rule.tier().toLowerCase(Locale.ROOT), cap, userId);
        }
        campaignMapper.expireLowPriorityNotifications(now.minusHours(48), userId);
    }

    private int numericCap(String value, int fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        String digits = value.replaceFirst("^\\D*(\\d+).*$", "$1");
        if (!StringUtils.hasText(digits)) return fallback;
        try {
            return Math.max(1, Math.min(Integer.parseInt(digits), 10000));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int defaultCap(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "high" -> 50;
            case "low" -> 30;
            default -> 200;
        };
    }

    private String text(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

}
