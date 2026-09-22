package ffdd.opsconsole.shared.security;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/** Resolves an auditable administrator identity from the authenticated security context. */
public final class AdminActorResolver {
    private AdminActorResolver() {
    }

    /** 整串就是一个电话号码(可选 + 前缀,可含空格/连字符)。 */
    private static final Pattern PHONE_LIKE = Pattern.compile("^\\+?\\d[\\d\\s-]{5,}\\d$");

    /**
     * 审计主体名。
     *
     * <p>🔴 <b>用户令牌的 {@code username} claim 装的是手机号</b>(见
     * {@code JwtTokenProvider.createUserToken}),而本方法此前对它一视同仁地原样返回。
     * 用户侧动作于是把**未脱敏手机号**写进审计日志,PC C1 用户画像的审计时间线再把它渲染
     * 出来 —— 隐私泄露,且审计 append-only,落库即永久留痕(zentao #229)。</p>
     *
     * <p>判据用令牌创建时显式写入的 {@code subjectType},分三种情形:</p>
     * <ul>
     *   <li><b>显式 {@code USER}</b> —— 用稳定且非 PII 的 {@code user:<id>},
     *       与 {@code AppUserProfileService} / {@code AppUserSecurityService} 等处的既有写法同源。
     *       手机号连落库的机会都没有,比打码更好:审计仍能精确定位到人。</li>
     *   <li><b>其它(含 {@code ADMIN})</b> —— 照旧优先用 {@code username}:管理员主体的
     *       登录名靠它,这是既有的、被大量契约测试钉住的行为。</li>
     *   <li><b>取不到 {@code subjectType}</b> —— 同样照旧用 {@code username},但若它长得像
     *       电话号码就打码。旧令牌可能没有该 claim,这是兜底。</li>
     * </ul>
     *
     * <p>展示侧另有一道同口径的打码({@code AuditTimeline}),因为审计是 append-only:
     * 写入侧修不好库里已有的历史行。</p>
     */
    public static String resolve(String trustedInternalFallback) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return trim(trustedInternalFallback);
        }
        Object principal = authentication.getPrincipal();
        String stableId = principal == null ? authentication.getName() : String.valueOf(principal);
        if (authentication.getDetails() instanceof Map<?, ?> values) {
            String subjectType = text(values.get("subjectType"));
            if ("USER".equalsIgnoreCase(subjectType) && StringUtils.hasText(stableId)) {
                return "user:" + stableId.trim();
            }
            String username = text(values.get("username"));
            if (StringUtils.hasText(username)) return maskPhone(username);
        }
        return StringUtils.hasText(stableId) ? "admin:" + stableId.trim() : trim(trustedInternalFallback);
    }

    /** 保留前 3 位与后 2 位,中间固定 4 个星号(与展示侧同口径)。非电话形状原样返回。 */
    static String maskPhone(String value) {
        if (!PHONE_LIKE.matcher(value).matches()) return value;
        boolean plus = value.startsWith("+");
        String digits = value.replaceAll("[\\s-]", "");
        String body = plus ? digits.substring(1) : digits;
        if (body.length() < 7 || body.length() > 15) return value;
        return (plus ? "+" : "") + body.substring(0, 3) + "****" + body.substring(body.length() - 2);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
