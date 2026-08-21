package ffdd.opsconsole.onboarding.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OnboardingCalibrationSandboxIsolationContractTest {
    @Test
    void mapperUsesProductionFenceAndRunScopedSandboxQueries() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/onboarding/mapper/OnboardingCalibrationMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains("source_environment='PRODUCTION' AND run_id=''",
                        "source_environment=#{sourceEnvironment}", "run_id=#{runId}",
                        "findForUpdateScoped", "insertScoped", "updateScoped", "findScoped");
    }

    @Test
    void schemaAndForwardMigrationCarryEnvironmentRunAndScopedUniqueness() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"), StandardCharsets.UTF_8);
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260816_onboarding_calibration_authority.sql"), StandardCharsets.UTF_8);
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"), StandardCharsets.UTF_8);

        for (String source : new String[] {schema, migration}) {
            assertThat(source).contains("source_environment VARCHAR(16)", "run_id VARCHAR(96)",
                    "uk_onboarding_calibration_scope (user_id,source_environment,run_id,device_id)",
                    "uk_onboarding_calibration_idem_scope",
                    "source_environment IN ('PRODUCTION','SANDBOX')");
        }
        assertThat(startup).contains("20260816_onboarding_calibration_authority.sql");
        assertThat(migration).contains("uk_onboarding_calibration_user_device",
                "DROP INDEX uk_onboarding_calibration_user_device",
                "information_schema.COLUMNS");
    }

    @Test
    void serviceProjectsCanonicalProvenanceAndRequiresSandboxRunFence() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/onboarding/application/OnboardingCalibrationService.java"),
                StandardCharsets.UTF_8);

        assertThat(service).contains("WheelSandboxProfile", "requireRunId()",
                "sourceEnvironment", "runId", "requestHash(userId, request, scope)",
                "userSandbox", "ONBOARDING_USER_ENVIRONMENT_MISMATCH");
    }
}
