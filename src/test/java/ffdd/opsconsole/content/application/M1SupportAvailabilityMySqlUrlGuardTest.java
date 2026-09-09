package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class M1SupportAvailabilityMySqlUrlGuardTest {
    @Test
    void rejectsCatalogAndNonLoopbackBeforeAnyConnectionIsOpened() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                M1SupportAvailabilityMySqlFixture.requireServerUrlWithoutCatalog("jdbc:mysql://127.0.0.1:3306/nexion"));
        assertThatIllegalArgumentException().isThrownBy(() ->
                M1SupportAvailabilityMySqlFixture.requireServerUrlWithoutCatalog("jdbc:mysql://192.168.8.10:3306/"));
        M1SupportAvailabilityMySqlFixture.requireServerUrlWithoutCatalog("jdbc:mysql://[::1]:3306/?useSSL=false");
    }
}