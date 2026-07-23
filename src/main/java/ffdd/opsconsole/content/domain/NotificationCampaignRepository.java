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

    boolean updateDraft(String campaignNo, NotificationCampaignDraftRequest request, long estimatedAudience, long expectedRevision, LocalDateTime now);

    long estimateAudience(NotificationAudienceTarget target, String currentPhase, LocalDateTime now);

    boolean deleteDraft(String campaignNo, long expectedRevision, LocalDateTime now);

    boolean scheduleDraft(String campaignNo, String schedule, String operator, long expectedRevision, LocalDateTime now);

    void updateStatus(String campaignNo, String status, String schedule, String operator, LocalDateTime now);

    int dispatchCampaignNotification(String campaignNo, String bizNo, String currentPhase, String trigger, String operator, LocalDateTime now);

    List<NotificationEventFact> listNotificationEventFactsByBizNo(
            String bizNo, String currentPhase, LocalDateTime now);

    default int countNotificationsByBizNo(String bizNo) {
        return 0;
    }

    List<String> listDueScheduledCampaignNos(LocalDateTime now, int limit);

    boolean claimScheduled(String campaignNo, LocalDateTime now);

    boolean claimForImmediateDispatch(String campaignNo, long expectedRevision, LocalDateTime now);

    boolean cancelScheduled(String campaignNo, String operator, long expectedRevision, LocalDateTime now);

    void completeDispatch(String campaignNo, String status, int sentCount, String schedule, String operator, LocalDateTime now);

    int recoverStaleSending(LocalDateTime staleBefore, LocalDateTime now);

    void applyRetention(LocalDateTime now);

    void applyRetentionForUser(Long userId);

    AppNotificationPage pageUserNotifications(Long userId, Long cursorId, String priority, int limit);

    boolean markNotificationRead(Long userId, Long notificationId);

    Optional<NotificationEventFact> lockNotificationEventFact(Long userId, Long notificationId);

    List<NotificationEventFact> lockUnreadNotificationEventFacts(Long userId);

    Optional<NotificationActionReceipt> findNotificationActionReceipt(String idempotencyKey);

    boolean recordNotificationAction(
            Long userId, Long notificationId, String action, String route, String idempotencyKey);

    int markAllNotificationsRead(Long userId);

    int clearReadNotifications(Long userId);

    List<NotificationCapRuleView> listCapRules();

    Optional<NotificationCapRuleView> findCapRule(String tier);

    void updateCapRule(String tier, String cap, String operator, LocalDateTime now);
}
