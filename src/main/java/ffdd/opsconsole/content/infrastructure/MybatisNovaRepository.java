package ffdd.opsconsole.content.infrastructure;

import ffdd.opsconsole.content.domain.NovaChannelView;
import ffdd.opsconsole.content.domain.NovaRepository;
import ffdd.opsconsole.content.domain.NovaSocialDistributionItem;
import ffdd.opsconsole.content.domain.NovaSocialPoolView;
import ffdd.opsconsole.content.domain.NovaTemplateView;
import ffdd.opsconsole.content.mapper.NovaMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisNovaRepository implements NovaRepository {
    private final NovaMapper mapper;

    @Override
    public void ensureTables() {
        mapper.createChannelTable();
        if (mapper.targetCtrColumnCount() == 0) {
            mapper.addTargetCtrColumn();
        }
        mapper.createTemplateTable();
        if (mapper.templateContentColumnCount() == 0) {
            mapper.addTemplateContentColumns();
        }
        mapper.quarantineIncompleteTemplates();
        mapper.createSocialDistributionTable();
        mapper.createSocialPoolTable();
    }

    @Override
    public List<NovaChannelView> channels() {
        return mapper.channels();
    }

    @Override
    public Map<String, Object> stats() {
        return mapper.stats();
    }

    @Override
    public Optional<NovaChannelView> channel(String key) {
        return Optional.ofNullable(mapper.channel(key));
    }

    @Override
    public int nextChannelOrder() {
        return mapper.maxChannelOrder() + 10;
    }

    @Override
    public void createChannel(String key, String name, String trigger, String tick, String cooldown,
                              BigDecimal ctr, boolean enabled, int sortOrder, String operator, String reason) {
        ensureTables();
        mapper.upsertChannel(key, name, trigger, tick, cooldown, ctr, enabled, sortOrder, operator, reason);
    }

    @Override
    public void updateChannel(String key, String name, String trigger, String tick, String cooldown,
                              BigDecimal ctr, boolean enabled, String operator, String reason) {
        ensureTables();
        mapper.updateChannel(key, name, trigger, tick, cooldown, ctr, enabled, operator, reason);
    }

    @Override
    public void updateChannelStatus(String key, boolean enabled, String operator, String reason) {
        ensureTables();
        mapper.updateChannelStatus(key, enabled, operator, reason);
    }

    @Override
    public void deleteChannel(String key, String operator, String reason) {
        ensureTables();
        mapper.deleteChannel(key, operator, reason);
    }

    @Override
    public List<NovaTemplateView> templates() {
        return mapper.templates();
    }

    @Override
    public Optional<NovaTemplateView> template(String channel) {
        return Optional.ofNullable(mapper.template(channel));
    }

    @Override
    public void createTemplate(String channel, String name, String cta, String version,
                               String titleZh, String bodyZh, String titleVi, String bodyVi,
                               String titleEn, String bodyEn, String operator, String reason) {
        ensureTables();
        mapper.upsertTemplate(channel, name, cta, version, titleZh, bodyZh, titleVi, bodyVi,
                titleEn, bodyEn, "DRAFT", operator, reason);
    }

    @Override
    public void updateTemplate(String channel, String name, String cta, String version,
                               String titleZh, String bodyZh, String titleVi, String bodyVi,
                               String titleEn, String bodyEn, String operator, String reason) {
        ensureTables();
        mapper.upsertTemplate(channel, name, cta, version, titleZh, bodyZh, titleVi, bodyVi,
                titleEn, bodyEn, "DRAFT", operator, reason);
    }

    @Override
    public void updateTemplateStatus(String channel, String status, String operator, String reason) {
        ensureTables();
        mapper.updateTemplateStatus(channel, status, operator, reason);
    }

    @Override
    public void deleteTemplate(String channel, String operator, String reason) {
        ensureTables();
        mapper.deleteTemplate(channel, operator, reason);
    }

    @Override
    public List<NovaSocialDistributionItem> socialDistribution() {
        return mapper.socialDistribution();
    }

    @Override
    public void upsertDistribution(String key, String name, int pct, String color, String operator, String reason) {
        ensureTables();
        mapper.upsertDistribution(key, name, pct, color, operator, reason);
    }

    @Override
    public List<NovaSocialPoolView> socialPools() {
        return mapper.socialPools();
    }

    @Override
    public Optional<NovaSocialPoolView> socialPool(String key) {
        return Optional.ofNullable(mapper.socialPool(key));
    }

    @Override
    public void upsertPool(String key, String name, String description, int count, String operator, String reason) {
        ensureTables();
        mapper.upsertPool(key, name, description, count, operator, reason);
    }
}
