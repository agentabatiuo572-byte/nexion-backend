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

    NotificationCampaignRow createCampaign(String campaignNo, NotificationCampaignCreateRequest request, long estimatedAudience, LocalDateTime now);

    void updateDraft(String campaignNo, NotificationCampaignDraftRequest request, long estimatedAudience, LocalDateTime now);

    long estimateAudience(NotificationAudienceTarget target, String currentPhase, LocalDateTime now);

    boolean deleteDraft(String campaignNo, LocalDateTime now);

    void updateStatus(String campaignNo, String status, String schedule, String operator, LocalDateTime now);

    int dispatchCampaignNotification(String campaignNo, String bizNo, String currentPhase, String trigger, String operator, LocalDateTime now);

    default int countNotificationsByBizNo(String bizNo) {
        return 0;
    }

    List<String> listDueScheduledCampaignNos(LocalDateTime now, int limit);

    boolean claimScheduled(String campaignNo, LocalDateTime now);

    boolean claimForImmediateDispatch(String campaignNo, LocalDateTime now);

    boolean cancelScheduled(String campaignNo, String operator, LocalDateTime now);

    void completeDispatch(String campaignNo, String status, int sentCount, String schedule, String operator, LocalDateTime now);

    int recoverStaleSending(LocalDateTime staleBefore, LocalDateTime now);

    void applyRetention(LocalDateTime now);

    void applyRetentionForUser(Long userId);

    AppNotificationPage pageUserNotifications(Long userId, Long cursorId, String priority, int limit);

    boolean markNotificationRead(Long userId, Long notificationId);

    int markAllNotificationsRead(Long userId);

    int clearReadNotifications(Long userId);

    List<NotificationCapRuleView> listCapRules();

    Optional<NotificationCapRuleView> findCapRule(String tier);

    void updateCapRule(String tier, String cap, String operator, LocalDateTime now);
}
