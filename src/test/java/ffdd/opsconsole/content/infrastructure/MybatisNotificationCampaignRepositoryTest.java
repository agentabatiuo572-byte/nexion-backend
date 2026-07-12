package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.domain.NotificationAudienceTarget;
import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import ffdd.opsconsole.content.mapper.NotificationCapRuleMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MybatisNotificationCampaignRepositoryTest {
    private final NotificationCampaignMapper campaignMapper = mock(NotificationCampaignMapper.class);
    private final NotificationCapRuleMapper capRuleMapper = mock(NotificationCapRuleMapper.class);
    private final MybatisNotificationCampaignRepository repository = new MybatisNotificationCampaignRepository(
            campaignMapper, capRuleMapper);
    private final LocalDateTime now = LocalDateTime.of(2026, 6, 18, 8, 30);

    @Test
    void estimateReturnsZeroWhenAuthoritativePhaseIsOutsideTargetRange() {
        long count = repository.estimateAudience(
                new NotificationAudienceTarget("P2", "P4", "vi", 30), "P5", now);

        assertThat(count).isZero();
        verify(campaignMapper, never()).countAudience(anyString(), anyInt(), any());
    }

    @Test
    void estimateUsesSameLanguageAndRegistrationPredicateWhenPhaseMatches() {
        when(campaignMapper.countAudience("vi", 30, now)).thenReturn(18420L);

        long count = repository.estimateAudience(
                new NotificationAudienceTarget("P2", "P4", "vi", 30), "P3", now);

        assertThat(count).isEqualTo(18420L);
        verify(campaignMapper).countAudience("vi", 30, now);
    }

    @Test
    void dispatchUsesBatchInsertWithTheSameAudiencePredicate() {
        NotificationCampaignEntity entity = new NotificationCampaignEntity();
        entity.setCampaignNo("CMP-1");
        entity.setStatus("SENDING");
        entity.setKind("system");
        entity.setTier("high");
        entity.setAudience("P2|P4|vi|30");
        entity.setName("公告");
        entity.setBodyZh("中文标题\n中文正文");
        entity.setBodyVi("Tiêu đề\nNội dung");
        entity.setBodyEn("English title\nEnglish body");
        when(campaignMapper.selectOne(any())).thenReturn(entity);
        when(campaignMapper.insertCampaignNotifications(
                "biz-1", "system", "high", "vi", 30, "中文标题", "中文正文", "Tiêu đề", "Nội dung",
                "English title", "English body", "", "", now)).thenReturn(18420);

        int inserted = repository.dispatchCampaignNotification(
                "CMP-1", "biz-1", "P3", "排期自动下发", "system", now);

        assertThat(inserted).isEqualTo(18420);
        verify(campaignMapper).insertCampaignNotifications(
                "biz-1", "system", "high", "vi", 30, "中文标题", "中文正文", "Tiêu đề", "Nội dung",
                "English title", "English body", "", "", now);
        verify(campaignMapper).markCampaignNotificationsDelivered("biz-1", now);
    }

    @Test
    void dispatchSkipsAllUsersWhenAuthoritativePhaseIsOutsideTargetRange() {
        NotificationCampaignEntity entity = new NotificationCampaignEntity();
        entity.setCampaignNo("CMP-1");
        entity.setStatus("SENDING");
        entity.setAudience("P2|P4|vi|30");
        when(campaignMapper.selectOne(any())).thenReturn(entity);

        int inserted = repository.dispatchCampaignNotification(
                "CMP-1", "biz-1", "P5", "排期自动下发", "system", now);

        assertThat(inserted).isZero();
        verify(campaignMapper, never()).insertCampaignNotifications(
                anyString(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void staleSendingRecoveryDelegatesToAtomicStatusRepair() {
        LocalDateTime staleBefore = now.minusMinutes(5);
        when(campaignMapper.recoverStaleSending(staleBefore, now)).thenReturn(2);

        assertThat(repository.recoverStaleSending(staleBefore, now)).isEqualTo(2);
        verify(campaignMapper).recoverStaleSending(staleBefore, now);
    }

    @Test
    void retentionUsesConfiguredFirstCapNumberAndServerSideLowTtl() {
        when(capRuleMapper.selectList(any())).thenReturn(List.of(
                cap("critical", "∞ 永不淘汰", true),
                cap("high", "50 条", false),
                cap("normal", "200 条", false),
                cap("low", "30 条 · TTL 24-48h", false)));

        repository.applyRetention(now);

        verify(campaignMapper).pruneNotificationsOverCap("high", 50, null);
        verify(campaignMapper).pruneNotificationsOverCap("normal", 200, null);
        verify(campaignMapper).pruneNotificationsOverCap("low", 30, null);
        verify(campaignMapper).expireLowPriorityNotifications(now.minusHours(48), null);
    }

    private NotificationCapRuleEntity cap(String tier, String label, boolean locked) {
        NotificationCapRuleEntity entity = new NotificationCapRuleEntity();
        entity.setTier(tier);
        entity.setCapLabel(label);
        entity.setPolicy("policy");
        entity.setLocked(locked ? 1 : 0);
        entity.setStatus(1);
        entity.setIsDeleted(0);
        return entity;
    }
}
