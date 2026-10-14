package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class SupportDefaultAvatarMigrationContractTest {
    @Test void missingStateSqlOnlyInsertsAdminIdAndGenderUpdateRejectsSoftDeletedState() {
        var config=new org.apache.ibatis.session.Configuration();
        config.addMapper(ffdd.opsconsole.platform.mapper.AdminAccountStateMapper.class);
        String namespace=ffdd.opsconsole.platform.mapper.AdminAccountStateMapper.class.getName()+".";
        var insert=config.getMappedStatement(namespace+"initializeMissingAvatarState")
                .getBoundSql(java.util.Map.of("adminId",4L));
        assertThat(insert.getSql().replaceAll("\\s+"," ").trim()).isEqualTo(
                "INSERT IGNORE INTO nx_admin_account_state (admin_id) VALUES (?)");
        assertThat(insert.getParameterMappings()).extracting(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                .containsExactly("adminId");
        var update=config.getMappedStatement(namespace+"updateAvatarGender")
                .getBoundSql(java.util.Map.of("adminId",4L,"gender","FEMALE"));
        assertThat(update.getSql().replaceAll("\\s+"," ").trim()).isEqualTo(
                "UPDATE nx_admin_account_state SET avatar_gender=? WHERE admin_id=? AND is_deleted=0");
        assertThat(update.getParameterMappings()).extracting(org.apache.ibatis.mapping.ParameterMapping::getProperty)
                .containsExactly("gender","adminId");
    }
    @Test void incrementalNullableGenderMigrationIsLoadedAfterExistingAvatarSchemaWithoutBackfill()throws Exception {
        var migration=Files.readString(Path.of("scripts/migrations/20261009_support_default_avatar.sql"));
        var startup=Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));
        assertThat(migration).contains("IF NOT EXISTS","column_name='avatar_gender'","ADD COLUMN avatar_gender VARCHAR(16) NULL");
        assertThat(migration.toUpperCase(java.util.Locale.ROOT)).doesNotContain("UPDATE NX_ADMIN","DELETE FROM","DROP TABLE");
        assertThat(startup.indexOf("20261009_support_default_avatar.sql")).isGreaterThan(startup.indexOf("20261001_support_enhancements_core.sql"));
    }
}
