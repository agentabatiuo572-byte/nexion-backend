package ffdd.opsconsole.auth.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AppUserRegistrationReferralLockContractTest {
    @Test
    void sponsorLookupUsesTheCanonicalUniqueReferralKeyInsteadOfAnExpressionRangeLock() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/mapper/AppUserRegistrationMapper.java"));

        int method = mapper.indexOf("UserEntity findSponsorForUpdate");
        String statement = mapper.substring(mapper.lastIndexOf("@Select", method), method);
        assertThat(statement).contains("WHERE referral_code=#{sponsorCode}");
        assertThat(statement).contains("FOR UPDATE");
        assertThat(statement).doesNotContain("UPPER(REPLACE(referral_code");
    }

    @Test
    void legacyCanonicalResolutionIsReadOnlyAndTheFinalLockUsesOneStoredUniqueKey() throws Exception {
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/mapper/AppUserRegistrationMapper.java"));

        int resolver = mapper.indexOf("findActiveSponsorsByCanonicalCode");
        int finalLock = mapper.indexOf("UserEntity findSponsorForUpdate");
        String resolverStatement = mapper.substring(Math.max(0, resolver - 700), resolver);
        String lockStatement = mapper.substring(Math.max(0, finalLock - 700), finalLock);
        assertThat(resolver).isGreaterThan(0);
        assertThat(resolverStatement).contains("UPPER(REPLACE(referral_code,'-',''))=#{canonicalCode}");
        assertThat(resolverStatement).doesNotContain("FOR UPDATE");
        assertThat(lockStatement).contains("WHERE referral_code=#{sponsorCode}");
        assertThat(lockStatement).contains("FOR UPDATE");
    }

    @Test
    void registrationOtpSchemaAndStartupMigrationProvideTheClientIpUsedByRateLimits() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260729_h003_registration_otp_client_ip.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        int table = schema.indexOf("CREATE TABLE IF NOT EXISTS nx_user_registration_otp");
        assertThat(table).isGreaterThanOrEqualTo(0);
        int nextTable = schema.indexOf("CREATE TABLE IF NOT EXISTS", table + 1);
        String registrationOtp = schema.substring(table, nextTable < 0 ? schema.length() : nextTable);
        assertThat(registrationOtp).contains("client_ip VARCHAR(64) NOT NULL");
        assertThat(migration).contains("ADD COLUMN client_ip VARCHAR(64) NOT NULL DEFAULT ''unknown''");
        assertThat(migration).contains("idx_user_registration_otp_ip");
        assertThat(startup).contains("20260729_h003_registration_otp_client_ip.sql");
    }

    @Test
    void userSchemaAndStartupMigrationProvideTheRegistrationClientIpSelectedByMybatisPlus() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260813_user_registration_client_ip.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        int table = schema.indexOf("CREATE TABLE IF NOT EXISTS nx_user (");
        assertThat(table).isGreaterThanOrEqualTo(0);
        int nextTable = schema.indexOf("CREATE TABLE IF NOT EXISTS", table + 1);
        String user = schema.substring(table, nextTable < 0 ? schema.length() : nextTable);
        assertThat(user).contains("client_ip VARCHAR(64) NOT NULL");
        assertThat(migration)
                .contains("TABLE_NAME = 'nx_user'")
                .contains("COLUMN_NAME = 'client_ip'")
                .contains("ADD COLUMN client_ip VARCHAR(64) NOT NULL DEFAULT ''unknown'' AFTER phone")
                .contains("MODIFY COLUMN client_ip VARCHAR(64) NOT NULL");
        assertThat(startup).contains("20260813_user_registration_client_ip.sql");
    }

    @Test
    void registrationIdentityIsCanonicalWhileOtpKeepsItsServerOwnedAudience() throws Exception {
        String schema = Files.readString(Path.of("scripts/schema.sql"));
        String mapper = Files.readString(Path.of(
                "src/main/java/ffdd/opsconsole/auth/mapper/AppUserRegistrationMapper.java"));
        String migration = Files.readString(Path.of(
                "scripts/migrations/20260812_auth_environment_identity_namespace.sql"));
        String startup = Files.readString(Path.of("scripts/apply_startup_schema_migrations.ps1"));

        assertThat(schema).contains("uk_user_phone (country_code, phone)")
                .doesNotContain("uk_user_phone_sandbox");
        assertThat(schema).contains("auth_environment VARCHAR(16) NOT NULL");
        assertThat(mapper).contains("auth_environment=#{authEnvironment}");
        assertThat(mapper).contains("consumeValidChallengeInEnvironment");
        assertThat(migration)
                .contains("DROP INDEX uk_user_phone_sandbox")
                .contains("ADD UNIQUE KEY uk_user_phone (country_code,phone)")
                .doesNotContain("ADD UNIQUE KEY uk_user_phone_sandbox");
        assertThat(migration).contains("DEFAULT ''LEGACY''");
        assertThat(startup).contains("20260812_auth_environment_identity_namespace.sql");
    }
}
