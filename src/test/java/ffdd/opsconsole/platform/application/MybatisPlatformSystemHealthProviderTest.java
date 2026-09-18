package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MybatisPlatformSystemHealthProviderTest {
    @Test void unlinkedProfileEvidenceRemainsInTotalAndIsExplicitlyUnresolved() {
        var mapper = mock(PlatformConfigItemMapper.class);
        when(mapper.selectA3EventBacklog()).thenReturn(Map.of("backlog", 185, "oldest_seconds", 848447, "audit_link_unresolved", 170));
        var health = new MybatisPlatformSystemHealthProvider(mapper).currentHealth().get(0);
        assertThat(health).containsEntry("tone", "bad").containsEntry("stale", false)
                .containsEntry("metric", "185 条 · 最久 848447 秒 · 170 条画像事件缺少审计关联，待核验");
    }
    @Test void ordinaryBacklogRetainsExistingDisplayAndThresholds() {
        var mapper = mock(PlatformConfigItemMapper.class);
        when(mapper.selectA3EventBacklog()).thenReturn(Map.of("backlog", 1, "oldest_seconds", 30, "audit_link_unresolved", 0));
        assertThat(new MybatisPlatformSystemHealthProvider(mapper).currentHealth().get(0))
                .containsEntry("tone", "ok").containsEntry("metric", "1 条 · 最久 30 秒");
    }
}
