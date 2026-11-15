package ffdd.opsconsole.user.domain;

import java.util.List;

public record UserRegistrationRiskOverview(
        UserRegistrationRiskStats stats,
        List<UserRegistrationRiskParamView> params,
        int captchaAfterSends,
        List<UserRegistrationRiskK1GuardView> k1Guards,
        long configVersion,
        String k1RejectCode,
        String k1Path,
        List<String> sources,
        List<String> redlines,
        List<String> captchaAlwaysScenes,
        boolean h5RegisterCaptcha,
        boolean h5RegisterMissingDeviceCaptcha) {
}
