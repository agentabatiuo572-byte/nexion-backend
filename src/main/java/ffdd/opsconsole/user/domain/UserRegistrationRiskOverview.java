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
    @com.fasterxml.jackson.annotation.JsonProperty(value = "authenticationBoundaries", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public java.util.Map<String, String> authenticationBoundaries() {
        return java.util.Map.of(
                "password", "密码登录先检查登录 IP 请求频率，再检查账号有效锁定；通过密码验证后另行检查密码重置要求。",
                "twoFactor", "两步验证提交先检查登录 IP 请求频率和账号有效锁定，再验证密码和验证码。",
                "loginOtpStart", "发起登录验证码前检查登录 IP 请求频率和账号有效锁定，之后另行检查发送间隔、发送次数及资格。",
                "loginOtpVerify", "提交登录验证码时，有效账号锁阻止验证码被消费；验证失败仍可记入账号失败计数。",
                "registrationOtp", "注册验证码使用独立的发送间隔、额度和验证码策略，不读取账号登录锁。",
                "loginIp", "登录保护入口按 IP 在一分钟内累计请求；达到 60 次时写入一分钟锁定，后续请求受限。这不是本页的账号失败阈值。",
                "accountLock", "锁定以仍有效的持久截止时间为准；长锁本身不触发密码重置。");
    }
}
