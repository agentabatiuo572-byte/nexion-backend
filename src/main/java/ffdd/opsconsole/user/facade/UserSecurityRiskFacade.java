package ffdd.opsconsole.user.facade;

public interface UserSecurityRiskFacade {
    UserSecurityRiskDecision evaluateLogin(Long userId);

    UserSecurityRiskDecision evaluateRegistrationOtp(
            String subjectKey,
            int otpSentLast24h,
            int secondsSinceLastOtp,
            boolean captchaPassed);
}
