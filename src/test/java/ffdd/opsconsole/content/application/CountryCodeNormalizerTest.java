package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CountryCodeNormalizerTest {
    @Test
    void normalizesIsoCallingCodesAndLocalizedCountryNames() {
        assertThat(CountryCodeNormalizer.normalize("86")).isEqualTo("CN");
        assertThat(CountryCodeNormalizer.normalize("+84")).isEqualTo("VN");
        assertThat(CountryCodeNormalizer.normalize("Việt Nam")).isEqualTo("VN");
        assertThat(CountryCodeNormalizer.normalize("cn")).isEqualTo("CN");
        assertThat(CountryCodeNormalizer.normalize("unknown")).isEmpty();
    }

    @Test
    void expandsConfiguredCountriesToLegacyAliasesForNotificationTargeting() {
        assertThat(CountryCodeNormalizer.aliasesFor(java.util.List.of("CN", "VN")))
                .contains("CN", "86", "+86", "VN", "84", "+84", "VIỆT NAM", "越南", "中国");
    }
}
