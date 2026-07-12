package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.mapper.NotificationCampaignMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

/** I5-to-I3 bridge. The existing notification unique key makes publication retries idempotent per user. */
@ApplicationService
@RequiredArgsConstructor
public class DisclosureReackNotificationService {
    private final NotificationCampaignMapper mapper;

    public int notifyPublished(String jurisdiction, String version, List<String> countryCodes, LocalDateTime now) {
        if (!StringUtils.hasText(jurisdiction) || !StringUtils.hasText(version) || now == null) {
            throw new IllegalArgumentException("DISCLOSURE_REACK_PUBLICATION_REQUIRED");
        }
        List<String> aliases = CountryCodeNormalizer.aliasesFor(countryCodes);
        if (aliases.isEmpty()) throw new IllegalArgumentException("DISCLOSURE_REACK_COUNTRIES_REQUIRED");
        String normalizedJurisdiction = jurisdiction.trim().toUpperCase(Locale.ROOT);
        String normalizedVersion = version.trim();
        String bizNo = "i5:reack:" + normalizedJurisdiction + ":" + normalizedVersion;
        int inserted = mapper.insertDisclosureReackNotifications(
                bizNo, normalizedJurisdiction, normalizedVersion, aliases, now);
        // Follow the existing I3 dispatch contract: queued rows become visible in the user notification stream.
        mapper.markCampaignNotificationsDelivered(bizNo, now);
        return inserted;
    }
}
