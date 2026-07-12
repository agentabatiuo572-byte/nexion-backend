package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface NovaRepository {
    void ensureTables();

    List<NovaChannelView> channels();

    Map<String, Object> stats();

    Optional<NovaChannelView> channel(String key);

    int nextChannelOrder();

    void createChannel(String key, String name, String trigger, String tick, String cooldown,
                       BigDecimal ctr, boolean enabled, int sortOrder, String operator, String reason);

    void updateChannel(String key, String name, String trigger, String tick, String cooldown,
                       BigDecimal ctr, boolean enabled, String operator, String reason);

    void updateChannelStatus(String key, boolean enabled, String operator, String reason);

    void deleteChannel(String key, String operator, String reason);

    List<NovaTemplateView> templates();

    Optional<NovaTemplateView> template(String channel);

    void createTemplate(String channel, String name, String cta, String version,
                        String titleZh, String bodyZh, String titleVi, String bodyVi,
                        String titleEn, String bodyEn, String operator, String reason);

    void updateTemplate(String channel, String name, String cta, String version,
                        String titleZh, String bodyZh, String titleVi, String bodyVi,
                        String titleEn, String bodyEn, String operator, String reason);

    void updateTemplateStatus(String channel, String status, String operator, String reason);

    void deleteTemplate(String channel, String operator, String reason);

    List<NovaSocialDistributionItem> socialDistribution();

    void upsertDistribution(String key, String name, int pct, String color, String operator, String reason);

    List<NovaSocialPoolView> socialPools();

    Optional<NovaSocialPoolView> socialPool(String key);

    void upsertPool(String key, String name, String description, int count, String operator, String reason);

    List<NovaSocialEventView> socialEvents();

    default List<NovaSocialEventView> socialEvents(String eventType, String status, int limit) {
        return socialEvents().stream()
                .filter(event -> eventType == null || eventType.isBlank() || eventType.equals(event.eventType()))
                .filter(event -> status == null || status.isBlank() || status.equals(event.status()))
                .limit(Math.max(1, limit))
                .toList();
    }

    default List<NovaSocialEventView> socialEvents(String eventType, String status, int limit, int offset) {
        return socialEvents(eventType, status, limit + Math.max(0, offset)).stream()
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();
    }

    default long countSocialEvents(String eventType, String status) {
        return socialEvents().stream()
                .filter(event -> eventType == null || eventType.isBlank() || eventType.equals(event.eventType()))
                .filter(event -> status == null || status.isBlank() || status.equals(event.status()))
                .count();
    }

    Optional<NovaSocialEventView> socialEvent(long id);

    Optional<NovaSocialEventView> socialEventBySource(String eventType, String sourceSystem, String sourceEventId);

    default boolean socialEventSourceExists(String eventType, String sourceSystem, String sourceEventId) {
        return socialEventBySource(eventType, sourceSystem, sourceEventId).isPresent();
    }

    void createSocialEvent(TrustedNovaSocialEvent source, String actorDisplay, String cityDisplay,
                           String amountDisplay, LocalDateTime expiresAt, String operator, String reason);

    default boolean tryCreateSocialEvent(TrustedNovaSocialEvent source, String actorDisplay, String cityDisplay,
                                         String amountDisplay, LocalDateTime expiresAt, String operator, String reason) {
        createSocialEvent(source, actorDisplay, cityDisplay, amountDisplay, expiresAt, operator, reason);
        return true;
    }

    void updateSocialEventStatus(long id, String status, String operator, String reason);

    void deleteSocialEvent(long id, String operator, String reason);

    int expireSocialEvents(LocalDateTime now);

    List<NovaSocialEventView> activeSocialEvents(LocalDateTime now);

    default List<NovaSocialEventView> activeSocialEventsByType(String eventType, LocalDateTime now, int limit) {
        return activeSocialEvents(now).stream()
                .filter(event -> eventType.equals(event.eventType()))
                .limit(Math.max(1, limit))
                .toList();
    }

    List<TrustedNovaSocialEvent> trustedSourceEvents(String sourceType, LocalDateTime since, LocalDateTime until);

    void markSocialEventDispatched(long id, LocalDateTime dispatchedAt);
}
