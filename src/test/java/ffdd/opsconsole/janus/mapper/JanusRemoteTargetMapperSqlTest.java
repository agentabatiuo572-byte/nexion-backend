package ffdd.opsconsole.janus.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.janus.domain.JanusRemoteTargetCreateCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class JanusRemoteTargetMapperSqlTest {
    @Test
    void explicitMigrationMatchesTheRuntimeTableWithoutSeedingBusinessTargets() throws Exception {
        String sql = Files.readString(Path.of("scripts/migrations/20260725_janus_remote_target.sql"));
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS nx_janus_remote_target");
        assertThat(sql).contains("catalog_version BIGINT NOT NULL AUTO_INCREMENT");
        assertThat(sql).contains("UNIQUE KEY uk_janus_remote_target_version(remote_target_key,remote_target_version)");
        assertThat(sql).contains("lock_version BIGINT NOT NULL DEFAULT 0");
        assertThat(sql).doesNotContain("INSERT INTO nx_janus_remote_target");
    }

    @Test
    void createUsesExpectedLatestVersionAndNeverOverwritesHistory() throws Exception {
        Method method = JanusRemoteTargetMapper.class.getMethod(
                "insertVersion", JanusRemoteTargetCreateCommand.class);
        String sql = String.join(" ", method.getAnnotation(Insert.class).value());
        assertThat(sql).contains("INSERT INTO nx_janus_remote_target");
        assertThat(sql).contains("MAX(x.remote_target_version)");
        assertThat(sql).contains("=#{c.expectedLatestVersion}");
        assertThat(sql).doesNotContain("ON DUPLICATE KEY UPDATE");
    }

    @Test
    void findBuildsAValidSelectStatementAfterCreatingANewVersion() throws Exception {
        Method method = JanusRemoteTargetMapper.class.getMethod("find", String.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value());
        assertThat(sql).startsWith("SELECT ");
        assertThat(sql).doesNotContain("SELECTt.");
        assertThat(sql).contains("FROM nx_janus_remote_target t");
    }

    @Test
    void disableUsesCasAndCancellationCannotMarkADeviceAsApplied() throws Exception {
        Method disable = JanusRemoteTargetMapper.class.getMethod(
                "disableVersion", String.class, int.class, long.class, long.class, String.class);
        String disableSql = String.join(" ", disable.getAnnotation(Update.class).value());
        assertThat(disableSql).contains("lock_version=#{expectedVersion}");
        assertThat(disableSql).contains("catalog_version=#{catalogVersion}");
        assertThat(disableSql).contains("status='ACTIVE'");

        Method cancel = JanusRemoteTargetMapper.class.getMethod(
                "cancelUnclaimedDevices", String.class, int.class, long.class);
        String cancelSql = String.join(" ", cancel.getAnnotation(Update.class).value());
        assertThat(cancelSql).contains("command_state='CANCELLED'");
        assertThat(cancelSql).contains("remote_target_version=#{version}");
        assertThat(cancelSql).contains("remote_target_catalog_version=#{catalogVersion}");
        assertThat(cancelSql).contains("acked_revision=GREATEST(acked_revision,desired_revision)");
        assertThat(cancelSql).doesNotContain("reported_status=");
        assertThat(cancelSql).doesNotContain("activated=");
    }
}
