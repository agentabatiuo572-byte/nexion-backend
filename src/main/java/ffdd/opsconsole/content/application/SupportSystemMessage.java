package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.i18n.InterfaceLanguages;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * BUG-519: 客服系统消息（未分配客服的回执、以及同一条消息的实时/持久化系统发送人）
 * 按目标用户的有效语言取值。语言代码沿用 {@link InterfaceLanguages}（zh/en/vi/id/ms/fil/ru/kk/uz）。
 *
 * <p>文案配置在同一键空间 {@code nexion.support.unbound.receipt-text.<code>}：任何一种受支持语言
 * 都可以只提供自己的键，后端按用户有效语言去读；该语言没提供时回退英文配置
 * （{@code ...receipt-text.en}），仍没有才用内置英文。因此存量那一条不带语言的
 * {@code nexion.support.unbound.receipt-text}（历史上是中文）只服务中文用户，不会让英文、
 * 越南语等用户读到中文。后端不内置 zh/en/vi 之外的自造译文。
 */
final class SupportSystemMessage {
    /**
     * 回执在 nx_conversation_message.origin 上的稳定身份。origin 是既有可空列（20261103 迁移加），
     * 目前只有 'WELCOME' 有特殊含义，其它值对 App 投影与 PC 会话都不产生副作用，
     * 所以用它做「同一条回执」的持久标记，而不是用会随语言变化的消息正文去重。
     */
    static final String RECEIPT_ORIGIN = "UNBOUND_ACK";
    static final String RECEIPT_KEY_PREFIX = "nexion.support.unbound.receipt-text";

    /** 内置默认值：中文保留历史文案，英文是缺译兜底，越南语只在未配置时使用。 */
    static final String RECEIPT_ZH = "已收到您的消息，正在为您安排专属客服，请稍候。";
    static final String RECEIPT_EN =
            "We have received your message. Your dedicated support advisor is being arranged, please wait a moment.";
    static final String RECEIPT_VI =
            "Chúng tôi đã nhận được tin nhắn của bạn. Chuyên viên hỗ trợ riêng đang được sắp xếp, vui lòng đợi trong giây lát.";

    private SupportSystemMessage() {
    }

    /**
     * 用户的有效界面语言：先把存量值归一成 {@link InterfaceLanguages} 的 base code，再要求它仍在
     * 现行启用清单里（PlatformExperienceConfigService 的接口语言清单，与 AppUserProfileService 保存
     * 语言时的判据一致）；不在清单、取不到或读失败一律按英文，绝不给用户发已停用的语言。
     */
    static String effectiveLanguage(String rawLanguage, Supplier<List<String>> enabledLanguages) {
        String code = InterfaceLanguages.baseCode(rawLanguage);
        try {
            List<String> enabled = enabledLanguages.get();
            return enabled != null && enabled.contains(code) ? code : "en";
        } catch (RuntimeException unavailable) {
            // 语言清单读不出来时不能因为这个读失败挡住回执；按英文兜底。
            return "en";
        }
    }

    static String effectiveLanguageTag(String rawLanguage, Supplier<List<String>> enabledLanguages) {
        return switch (effectiveLanguage(rawLanguage, enabledLanguages)) {
            case "zh" -> "zh-CN";
            case "vi" -> "vi-VN";
            case "id" -> "id-ID";
            case "ms" -> "ms-MY";
            case "fil" -> "fil-PH";
            case "ru" -> "ru-RU";
            case "kk" -> "kk-KZ";
            case "uz" -> "uz-UZ";
            default -> "en-US";
        };
    }

    /**
     * 未分配客服的回执正文：先读该语言的配置，没有就回退英文配置，再没有才用内置文案
     * （中文用存量单条配置或内置中文，越南语用内置越南语，其余语言用内置英文）。
     */
    static String unboundReceipt(String language, String legacy, Environment environment) {
        String code = InterfaceLanguages.baseCode(language);
        String configured = environment.getProperty(RECEIPT_KEY_PREFIX + "." + code);
        if (StringUtils.hasText(configured)) return configured.trim();
        if ("zh".equals(code)) {
            // 存量单条键历史上就是中文，只服务中文用户。
            return StringUtils.hasText(legacy) ? legacy.trim() : RECEIPT_ZH;
        }
        String english = environment.getProperty(RECEIPT_KEY_PREFIX + ".en");
        if (StringUtils.hasText(english)) return english.trim();
        return "vi".equals(code) ? RECEIPT_VI : RECEIPT_EN;
    }

    /** 系统发送人显示名；只有已知的中文、越南语用对应写法，其余语言用英文。 */
    static String senderName(String language) {
        return switch (InterfaceLanguages.baseCode(language)) {
            case "zh" -> "系统";
            case "vi" -> "Hệ thống";
            default -> "System";
        };
    }
}
