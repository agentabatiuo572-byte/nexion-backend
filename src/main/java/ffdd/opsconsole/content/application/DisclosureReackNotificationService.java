package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** I5-to-I3 bridge. Each mapping activation is idempotent without suppressing a later switch to the same version. */
@ApplicationService
@RequiredArgsConstructor
public class DisclosureReackNotificationService {
    private final NotificationCampaignMapper mapper;
    private final EventOutboxService eventOutboxService;

    @Transactional
    public int notifyPublished(
            String jurisdiction, String fromVersion, String version,
            List<String> countryCodes, String activationKey, LocalDateTime now) {
        if (!StringUtils.hasText(jurisdiction) || !StringUtils.hasText(fromVersion) || !StringUtils.hasText(version)
                || !StringUtils.hasText(activationKey) || now == null) {
            throw new IllegalArgumentException("DISCLOSURE_REACK_PUBLICATION_REQUIRED");
        }
        List<String> aliases = CountryCodeNormalizer.aliasesFor(countryCodes);
        if (aliases.isEmpty()) throw new IllegalArgumentException("DISCLOSURE_REACK_COUNTRIES_REQUIRED");
        String normalizedJurisdiction = jurisdiction.trim().toUpperCase(Locale.ROOT);
        String normalizedVersion = version.trim();
        String activationId = DisclosureContentHash.ofParts(activationKey.trim()).substring(0, 24);
        String bizNo = "i5:reack:" + normalizedJurisdiction + ":" + normalizedVersion + ":" + activationId;
        int inserted = mapper.insertDisclosureReackNotifications(
                bizNo, normalizedJurisdiction, normalizedVersion, aliases, now);
        // Follow the existing I3 dispatch contract: queued rows become visible in the user notification stream.
        mapper.markCampaignNotificationsDelivered(bizNo, now);
        eventOutboxService.publish(
                "DISCLOSURE_REACK", bizNo, "disclosure.reack_triggered",
                java.util.Map.of(
                        "jurisdiction", normalizedJurisdiction,
                        "from_version", fromVersion.trim(),
                        "to_version", normalizedVersion,
                        "affected_user_count", inserted));
        return inserted;
    }
}
