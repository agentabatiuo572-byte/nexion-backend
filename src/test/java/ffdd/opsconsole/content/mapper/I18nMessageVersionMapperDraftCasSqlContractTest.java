package ffdd.opsconsole.content.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class I18nMessageVersionMapperDraftCasSqlContractTest {

    @Test
    void draftRetirementUsesMessageKeyAndExpectedVersionAsTheMysqlCasPredicate() {
        Method cas = Arrays.stream(I18nMessageVersionMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("retireDraftCas"))
                .findFirst()
                .orElseThrow();
        String sql = String.join(" ", cas.getAnnotation(Update.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql)
                .contains("update nx_i18n_message_version")
                .contains("message_key = #{messagekey}")
                .contains("version_no = #{expectedversionno}")
                .contains("status = 'draft'")
                .contains("is_deleted = 0");
        assertThat(sql).doesNotContain("where id =");
    }

    @Test
    void nextDraftInsertOnlySelectsTheStillCurrentExpectedVersion() {
        Method cas = Arrays.stream(I18nMessageVersionMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("insertDraftCas"))
                .findFirst()
                .orElseThrow();
        String sql = String.join(" ", cas.getAnnotation(Insert.class).value())
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql)
                .contains("insert into nx_i18n_message_version")
                .contains("select #{messagekey}, #{nextversionno}")
                .contains("expected.message_key = #{messagekey}")
                .contains("expected.version_no = #{expectedversionno}")
                .contains("expected.is_deleted = 0")
                .contains("not exists")
                .contains("newer.version_no > expected.version_no");
    }

    @Test
    void schemaKeepsMessageKeyAndVersionNumberUniqueAcrossAllInstances() throws Exception {
        String schema = Files.readString(Path.of("scripts", "schema.sql"))
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(schema)
                .contains("create table if not exists nx_i18n_message_version")
                .contains("unique key uk_i18n_message_version (message_key, version_no)");
    }
}
