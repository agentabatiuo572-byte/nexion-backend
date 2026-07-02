package ffdd.opsconsole.content.domain;

import java.math.BigDecimal;
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

    void createTemplate(String channel, String name, String cta, String version, String operator, String reason);

    void updateTemplateStatus(String channel, String status, String operator, String reason);

    List<NovaSocialDistributionItem> socialDistribution();

    void upsertDistribution(String key, String name, int pct, String color, String operator, String reason);

    List<NovaSocialPoolView> socialPools();

    Optional<NovaSocialPoolView> socialPool(String key);

    void upsertPool(String key, String name, String description, int count, String operator, String reason);
}
