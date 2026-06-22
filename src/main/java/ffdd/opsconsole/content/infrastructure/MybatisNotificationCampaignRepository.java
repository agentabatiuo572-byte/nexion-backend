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

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }
}
