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
        String registrationOtp = schema.substring(table, schema.indexOf("CREATE TABLE IF NOT EXISTS nx_c5", table));
        assertThat(registrationOtp).contains("client_ip VARCHAR(64) NOT NULL");
        assertThat(migration).contains("ADD COLUMN client_ip VARCHAR(64) NOT NULL DEFAULT ''unknown''");
        assertThat(migration).contains("idx_user_registration_otp_ip");
        assertThat(startup).contains("20260729_h003_registration_otp_client_ip.sql");
    }
}
