package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.domain.PlatformConfigItem;
import ffdd.opsconsole.platform.domain.PlatformConfigRepository;
import ffdd.opsconsole.platform.mapper.PlatformConfigItemMapper;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlatformConfigInsertIfAbsentTest {
    @Test
    void facadeDelegatesToTheAtomicRepositoryInsertWithoutReadingOrUpdatingAnExistingRow() {
        PlatformConfigRepository repository = mock(PlatformConfigRepository.class);
        when(repository.insertIfAbsent(any())).thenReturn(true);
        PlatformConfigFacadeAdapter facade = new PlatformConfigFacadeAdapter(repository);

        boolean inserted = facade.insertAdminValueIfMissing(
                "team.rank-how.published", "{\"status\":\"PUBLISHED\"}", "JSON",
                "published_content", "development baseline");

        ArgumentCaptor<PlatformConfigItem> item = ArgumentCaptor.forClass(PlatformConfigItem.class);
        verify(repository).insertIfAbsent(item.capture());
        assertThat(inserted).isTrue();
        assertThat(item.getValue().configKey()).isEqualTo("team.rank-how.published");
        assertThat(item.getValue().configValue()).isEqualTo("{\"status\":\"PUBLISHED\"}");
        assertThat(item.getValue().status()).isEqualTo(1);
        assertThat(item.getValue().visibility()).isEqualTo("ADMIN");
    }

    @Test
    void mapperUsesTheUniqueKeyAsAnAtomicInsertOnlyGate() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        Method method = PlatformConfigItemMapper.class.getMethod(
                "insertIfConfigKeyAbsent",
                String.class, String.class, String.class, String.class, String.class, String.class, Integer.class);
        String sql = String.join(" ", method.getAnnotation(Insert.class).value());

        assertThat(schema).contains("UNIQUE KEY uk_config_key (config_key)");
        assertThat(sql).contains("INSERT IGNORE INTO nx_config_item")
                .doesNotContain("UPDATE")
                .doesNotContain("config_value=VALUES(config_value)");
    }
}
