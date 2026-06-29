package ffdd.opsconsole.content.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.content.domain.NotificationCampaignRepository;
import ffdd.opsconsole.content.domain.NotificationCampaignRow;
import ffdd.opsconsole.content.domain.NotificationCapRuleView;
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

    private static final List<CapSeed> CAP_SEEDS = List.of(
            cap("critical", "∞ 永不淘汰", "合规重确认 / 风控异动 / 资金账户异动 - 合规硬约束:不可调降,一条都不能丢", true, 10),
            cap("high", "50 条", "tier 内 LIFO · 高优运营事件优先保留", false, 20),
            cap("normal", "200 条", "通知中心总上限 · 常规运营公告", false, 30),
            cap("low", "30 条 · TTL 24-48h", "教程提示等低优 · 数量+时间双闸,过期自动清", false, 40));

    private static final List<CampaignSeed> CAMPAIGN_SEEDS = List.of(
            campaign("CMP-2618", "6/15 钱包维护窗口公告", "system", "high", "全量", "182K", "SCHEDULED", "06-15 02:00 排期", "-", "-",
                    "Wallet maintenance window on Jun 15. Withdrawals may be delayed.",
                    "6 月 15 日钱包维护窗口,提现到账可能延迟。", "/me/notifications/maintenance-0615", "0"),
            campaign("CMP-2617", "SFC 风险披露重新确认", "compliance", "critical", "SFC 辖区 · 未重确认用户", "9.4K", "SCHEDULED", "06-12 10:00 排期", "-", "-",
                    "Please review and acknowledge the updated SFC risk disclosure.",
                    "请阅读并确认新版 SFC 风险披露。", "/me/risk-disclosure", "0"),
            campaign("CMP-2615", "KYC Express 补资料提醒", "kyc", "high", "近 30 天提现 >$1k", "12.4K", "SENT", "05-31 16:00 已发", "12.4K", "9.1K",
                    "Your KYC Express review needs one more document.",
                    "KYC Express 审核还需要补充一项资料。", "/me/kyc", "0"),
            campaign("CMP-2612", "每周任务刷新", "ops", "normal", "P3 阶段活跃用户", "178K", "SENT", "05-27 09:00 已发", "178K", "104K",
                    "Weekly quests refreshed. Complete them to keep your rhythm.",
                    "每周任务已刷新,完成任务保持增长节奏。", "/earn/quests", "0"),
            campaign("CMP-2609", "服务条款更新确认", "compliance", "critical", "全量", "181K", "SENT", "05-18 12:00 已发", "181K", "152K",
                    "Terms of service updated. Please review the latest version.",
                    "服务条款已更新,请查看最新版本。", "/trust", "0"),
            campaign("CMP-2606", "Learn 新课上线提醒", "learn", "low", "注册 ≤14 天", "20.6K", "SENT", "05-08 18:30 已发", "20.6K", "7.8K",
                    "New Learn lessons are available for beginners.",
                    "Learn 新手课程已上线。", "/learn", "0"),
            campaign("CMP-2619", "复投活动预热", "ops", "normal", "全量", "-", "DRAFT", "-", "-", "-",
                    "A new reinvestment event is being prepared.",
                    "新的复投活动正在准备中。", "/reinvest", "1200"));

    @Override
    public void ensureSeedData(LocalDateTime now) {
        for (CapSeed seed : CAP_SEEDS) {
            ensureCap(seed, now);
        }
        for (CampaignSeed seed : CAMPAIGN_SEEDS) {
            ensureCampaign(seed, now);
        }
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
    public NotificationCampaignRow createCampaign(String campaignNo, NotificationCampaignCreateRequest request, LocalDateTime now) {
        NotificationCampaignEntity entity = new NotificationCampaignEntity();
        entity.setCampaignNo(campaignNo);
        entity.setName(request.name().trim());
        entity.setKind("system");
        entity.setTier(request.tier().trim().toLowerCase(Locale.ROOT));
        entity.setAudience(request.audience().trim());
        entity.setReachLabel("-");
        entity.setStatus("DRAFT");
        entity.setScheduleText("-");
        entity.setSentLabel("-");
        entity.setReadLabel("-");
        entity.setBodyEn(notificationBody(request.title(), request.content()));
        entity.setBodyZh(notificationBody(request.title(), request.content()));
        entity.setSwipeTo("-");
        entity.setBudgetUsd(request.budget());
        entity.setCreatedBy(operator(request.operator()));
        entity.setLastOperator(operator(request.operator()));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        campaignMapper.insert(entity);
        return findCampaign(campaignNo).orElse(toRow(entity));
    }

    @Override
    public void updateDraft(String campaignNo, NotificationCampaignDraftRequest request, LocalDateTime now) {
        NotificationCampaignEntity entity = findEntity(campaignNo);
        if (entity == null) {
            return;
        }
        entity.setName(request.title().trim());
        entity.setTier(request.tier().trim().toLowerCase(Locale.ROOT));
        entity.setAudience(request.audience().trim());
        entity.setScheduleText(StringUtils.hasText(request.schedule()) ? request.schedule().trim() : "-");
        entity.setBodyEn(request.body().trim());
        entity.setBodyZh(request.body().trim());
        entity.setBudgetUsd(request.budget());
        entity.setStatus("DRAFT");
        entity.setLastOperator(operator(request.operator()));
        entity.setUpdatedAt(now);
        campaignMapper.updateById(entity);
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
        entity.setUpdatedAt(now);
        campaignMapper.updateById(entity);
    }

    @Override
    public int dispatchCampaignNotification(String campaignNo, String bizNo, String trigger, String operator, LocalDateTime now) {
        NotificationCampaignEntity entity = findEntity(campaignNo);
        if (entity == null || "CANCELLED".equalsIgnoreCase(entity.getStatus())) {
            return 0;
        }
        String title = "[" + entity.getTier().toUpperCase(Locale.ROOT) + "] " + entity.getName();
        String body = notificationBody(entity, trigger);
        int inserted = campaignMapper.insertCampaignNotification(bizNo, title, body, now);
        entity.setStatus("SENDING");
        entity.setScheduleText(StringUtils.hasText(trigger) ? trigger.trim() : "立即下发中");
        entity.setSentLabel("1");
        entity.setLastOperator(operator(operator));
        entity.setUpdatedAt(now);
        campaignMapper.updateById(entity);
        return inserted;
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

    private void ensureCampaign(CampaignSeed seed, LocalDateTime now) {
        if (findEntity(seed.no()) != null) {
            return;
        }
        NotificationCampaignEntity entity = new NotificationCampaignEntity();
        entity.setCampaignNo(seed.no());
        entity.setName(seed.name());
        entity.setKind(seed.kind());
        entity.setTier(seed.tier());
        entity.setAudience(seed.audience());
        entity.setReachLabel(seed.reach());
        entity.setStatus(seed.status());
        entity.setScheduleText(seed.schedule());
        entity.setSentLabel(seed.sent());
        entity.setReadLabel(seed.read());
        entity.setBodyEn(seed.bodyEn());
        entity.setBodyZh(seed.bodyZh());
        entity.setSwipeTo(seed.swipeTo());
        entity.setBudgetUsd(new java.math.BigDecimal(seed.budgetUsd()));
        entity.setCreatedBy("seed");
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        campaignMapper.insert(entity);
    }

    private void ensureCap(CapSeed seed, LocalDateTime now) {
        if (findCapEntity(seed.tier()) != null) {
            return;
        }
        NotificationCapRuleEntity entity = new NotificationCapRuleEntity();
        entity.setTier(seed.tier());
        entity.setCapLabel(seed.cap());
        entity.setPolicy(seed.policy());
        entity.setLocked(seed.locked() ? 1 : 0);
        entity.setSortOrder(seed.sortOrder());
        entity.setStatus(1);
        entity.setLastOperator("seed");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setIsDeleted(0);
        capRuleMapper.insert(entity);
    }

    private NotificationCampaignRow toRow(NotificationCampaignEntity entity) {
        return new NotificationCampaignRow(
                entity.getCampaignNo(),
                entity.getName(),
                entity.getKind(),
                entity.getTier(),
                entity.getAudience(),
                entity.getReachLabel(),
                toViewStatus(entity.getStatus()),
                entity.getScheduleText(),
                entity.getSentLabel(),
                entity.getReadLabel(),
                entity.getBodyEn(),
                entity.getBodyZh(),
                entity.getSwipeTo(),
                entity.getBudgetUsd());
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

    private String notificationBody(NotificationCampaignEntity entity, String trigger) {
        String body = StringUtils.hasText(entity.getBodyZh()) ? entity.getBodyZh() : entity.getBodyEn();
        String prefix = StringUtils.hasText(trigger) ? trigger.trim() + "\n" : "";
        return prefix + body;
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private static CapSeed cap(String tier, String cap, String policy, boolean locked, int sortOrder) {
        return new CapSeed(tier, cap, policy, locked, sortOrder);
    }

    private static CampaignSeed campaign(String no, String name, String kind, String tier, String audience, String reach, String status, String schedule, String sent, String read, String bodyEn, String bodyZh, String swipeTo, String budgetUsd) {
        return new CampaignSeed(no, name, kind, tier, audience, reach, status, schedule, sent, read, bodyEn, bodyZh, swipeTo, budgetUsd);
    }

    private record CapSeed(String tier, String cap, String policy, boolean locked, int sortOrder) {
    }

    private record CampaignSeed(String no, String name, String kind, String tier, String audience, String reach, String status, String schedule, String sent, String read, String bodyEn, String bodyZh, String swipeTo, String budgetUsd) {
    }
}
