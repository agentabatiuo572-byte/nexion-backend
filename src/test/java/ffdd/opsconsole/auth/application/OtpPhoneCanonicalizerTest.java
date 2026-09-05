package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OtpPhoneCanonicalizerTest {

    @Test
    void localTrunkPrefixesCannotCreateDifferentProviderOrRateLimitIdentities() {
        assertThat(OtpPhoneCanonicalizer.toE164Digits("+84", "0901234567"))
                .isEqualTo("84901234567")
                .isEqualTo(OtpPhoneCanonicalizer.toE164Digits("+84", "901234567"));
    }

    @Test
    void rejectsMalformedOrEmptyDestinations() {
        assertThatThrownBy(() -> OtpPhoneCanonicalizer.toE164Digits("+84", "000901234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP_PHONE_DESTINATION_INVALID");
        assertThatThrownBy(() -> OtpPhoneCanonicalizer.toE164Digits("+0", "901234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP_PHONE_DESTINATION_INVALID");
    }

    @Test
    void acceptsOnlyVietnamAndChinaAndFailsClosedForOtherCountryCodes() {
        assertThat(OtpPhoneCanonicalizer.toE164Digits("+86", "13800138000"))
                .isEqualTo("8613800138000");
        assertThatThrownBy(() -> OtpPhoneCanonicalizer.toE164Digits("+1", "14155552671"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP_PHONE_DESTINATION_INVALID");
        assertThatThrownBy(() -> OtpPhoneCanonicalizer.toE164Digits("+81", "09012345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP_PHONE_DESTINATION_INVALID");
        assertThatThrownBy(() -> OtpPhoneCanonicalizer.toE164Digits("+39", "0212345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP_PHONE_DESTINATION_INVALID");
        assertThatThrownBy(() -> OtpPhoneCanonicalizer.toE164Digits("+39", "3123456789"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("OTP_PHONE_DESTINATION_INVALID");
    }

    @Test
    void exposesTheSameCountryWhitelistUsedByTheFormalApp() {
        assertThat(OtpPhoneCanonicalizer.isSupportedCountryCode("+84")).isTrue();
        assertThat(OtpPhoneCanonicalizer.isSupportedCountryCode("86")).isTrue();
        assertThat(OtpPhoneCanonicalizer.isSupportedCountryCode("1")).isFalse();
        assertThat(OtpPhoneCanonicalizer.isSupportedCountryCode("+81")).isFalse();
        assertThat(OtpPhoneCanonicalizer.isSupportedCountryCode("+39")).isFalse();
        assertThat(OtpPhoneCanonicalizer.isSupportedCountryCode("+0001")).isFalse();
    }
}
