package ffdd.opsconsole.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ffdd.opsconsole.content.mapper.HelpArticleMapper;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class MybatisSessionTemplateRepositoryTest {
    private final MybatisSessionTemplateRepository repository = new MybatisSessionTemplateRepository(mock(HelpArticleMapper.class));

    @Test
    void archivedStatusRoundTripsAsDedicatedDatabaseState() throws Exception {
        assertThat(invoke("toDbStatus", new Class<?>[]{String.class}, "archived")).isEqualTo(2);
        assertThat(invoke("toScriptStatus", new Class<?>[]{Integer.class}, 2)).isEqualTo("archived");
        assertThat(invoke("normalizeStatus", new Class<?>[]{String.class}, "ARCHIVED")).isEqualTo("archived");
    }

    @Test
    void scriptAndReplyQueriesExposeAndFilterArchivedState() throws Exception {
        String scripts = sql("pageSessionScripts", String.class, String.class, long.class, long.class);
        String replies = sql("pageSessionReplyTemplates", String.class, String.class, String.class, long.class, long.class);

        assertThat(scripts)
                .contains("WHEN 2 THEN 'archived'")
                .contains("WHEN 'archived' THEN 2");
        assertThat(replies)
                .contains("WHEN 2 THEN 'archived'")
                .contains("WHEN 'archived' THEN 2");
    }

    private Object invoke(String name, Class<?>[] parameterTypes, Object argument) throws Exception {
        Method method = MybatisSessionTemplateRepository.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(repository, argument);
    }

    private String sql(String name, Class<?>... parameterTypes) throws Exception {
        return String.join("\n", HelpArticleMapper.class.getMethod(name, parameterTypes).getAnnotation(Select.class).value());
    }
}
