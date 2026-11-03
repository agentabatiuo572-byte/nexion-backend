package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.shared.i18n.InterfaceLanguages;
import java.util.Locale;

final class NexGridOtpMessage {
    private static final String SECURITY_NOTICE =
            "Never share this code with anyone, including UVEL Support.";

    private NexGridOtpMessage() {
    }

    static String render(String countryCode, String challengeNo, String code, int ttlMinutes) {
        return render(countryCode, challengeNo, code, ttlMinutes, null);
    }

    static String render(String countryCode, String challengeNo, String code, int ttlMinutes, String language) {
        if (code == null || !code.matches("\\d{6}") || ttlMinutes < 1 || ttlMinutes > 15) {
            throw new IllegalArgumentException("USER_OTP_MESSAGE_INPUT_INVALID");
        }
        String normalizedCountryCode = countryCode == null
                ? ""
                : countryCode.trim().replace(" ", "").replaceFirst("^\\+", "");
        String accountLanguage = language == null
                ? ("84".equals(normalizedCountryCode) ? "vi" : "en") : InterfaceLanguages.baseCode(language);
        String template = switch (accountLanguage) {
            case "zh" -> "UVEL 验证码：%s，%d 分钟内有效。请勿向任何人透露。";
            case "vi" -> "Mã xác minh của bạn là %s, có hiệu lực trong %d phút. [UVEL]";
            case "id" -> "Kode verifikasi UVEL Anda: %s. Berlaku selama %d menit. Jangan bagikan kode ini.";
            case "ms" -> "Kod pengesahan UVEL anda: %s. Sah selama %d minit. Jangan kongsi kod ini.";
            case "fil" -> "Ang iyong UVEL verification code ay %s. Valid ito sa loob ng %d minuto. Huwag ibahagi ang code.";
            case "ru" -> "Код подтверждения UVEL: %s. Действителен %d мин. Никому не сообщайте код.";
            case "kk" -> "UVEL растау коды: %s. Код %d минут жарамды. Оны ешкімге айтпаңыз.";
            case "uz" -> "UVEL tasdiqlash kodi: %s. Kod %d daqiqa amal qiladi. Uni hech kimga bermang.";
            default -> "UVEL verification code: %s. Expires in %d min. " + SECURITY_NOTICE;
        };
        return String.format(Locale.ROOT, template, code, ttlMinutes);
    }
}
