package ffdd.opsconsole.content.domain;

import ffdd.opsconsole.content.dto.NotificationCampaignCreateRequest;
import ffdd.opsconsole.content.dto.NotificationCampaignDraftRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NotificationCampaignRepository {
    void ensureSeedData(LocalDateTime now);

    List<NotificationCampaignRow> listCampaigns();

    Optional<NotificationCampaignRow> findCampaign(String campaignNo);

    NotificationCampaignRow createCampaign(String campaignNo, NotificationCampaignCreateRequest request, LocalDateTime now);

    void updateDraft(String campaignNo, NotificationCampaignDraftRequest request, LocalDateTime now);

    void updateStatus(String campaignNo, String status, String schedule, String operator, LocalDateTime now);

    List<NotificationCapRuleView> listCapRules();

    Optional<NotificationCapRuleView> findCapRule(String tier);

    void updateCapRule(String tier, String cap, String operator, LocalDateTime now);
}
