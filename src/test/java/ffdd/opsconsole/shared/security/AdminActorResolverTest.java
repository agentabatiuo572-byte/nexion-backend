package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * zentao #229:审计主体名不得泄露用户手机号。
 *
 * <p>用户令牌的 {@code username} claim 装的是**手机号**(见
 * {@code JwtTokenProvider.createUserToken}),而 {@code resolve()} 此前对它一视同仁地
 * 原样返回。用户侧动作于是把未脱敏手机号写进审计日志,PC C1 用户画像的审计时间线再把它
 * 渲染出来 —— 隐私泄露且永久留痕。本门钉住:用户主体只能得到稳定非 PII 的 {@code user:<id>},
 * 只有管理员主体才使用其登录名。</p>
 */
class AdminActorResolverTest {

    private static final String USER_PHONE = "13800000007";

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    /** 构造一条与 {@code JwtAuthenticationFilter} 同形的认证对象。 */
    private void authenticate(String subjectType, String username, String principal) {
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        var details = new java.util.LinkedHashMap<String, String>();
        if (subjectType != null) details.put("subjectType", subjectType);
        if (username != null) details.put("username", username);
        authentication.setDetails(Map.copyOf(details));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void neverPublishesAUserPhoneAsTheAuditActor() {
        authenticate("USER", USER_PHONE, "7");

        String actor = AdminActorResolver.resolve("system");

        assertThat(actor).isEqualTo("user:7");
        assertThat(actor).doesNotContain(USER_PHONE);
    }

    @Test
    void keepsUsingAnAdministratorsLoginName() {
        authenticate("ADMIN", "suadmin", "1");

        assertThat(AdminActorResolver.resolve("system")).isEqualTo("suadmin");
    }

    @Test
    void treatsAMissingSubjectTypeAsAnUnknownTokenAndStillMasksAPhone() {
        // 旧令牌可能没有 subjectType claim。此时保留既有优先级(用 username),
        // 但手机号必须打码 —— 任何路径都不能让未脱敏手机号成为审计主体。
        authenticate(null, USER_PHONE, "7");

        String actor = AdminActorResolver.resolve("system");

        assertThat(actor).isEqualTo("138****07");
        assertThat(actor).doesNotContain(USER_PHONE);
    }

    @Test
    void fallsBackToTheTrustedInternalNameForAnAnonymousCaller() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "fixture", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(AdminActorResolver.resolve("system")).isEqualTo("system");
        assertThat(AdminActorResolver.resolve(null)).isNull();
    }

    @Test
    void stillResolvesAnAdminWithoutAUsernameClaimFromItsPrincipal() {
        authenticate("ADMIN", null, "42");

        assertThat(AdminActorResolver.resolve("system")).isEqualTo("admin:42");
    }
}
