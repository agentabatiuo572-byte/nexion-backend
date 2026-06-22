package ffdd.opsconsole.user.domain;

import java.util.List;

public record UserRegistrationRiskOverview(
        UserRegistrationRiskStats stats,
        List<UserRegistrationRiskParamView> params,
        List<UserRegistrationRiskK1GuardView> k1Guards,
        String k1RejectCode,
        String k1Path,
        List<String> sources,
        List<String> redlines) {
}
