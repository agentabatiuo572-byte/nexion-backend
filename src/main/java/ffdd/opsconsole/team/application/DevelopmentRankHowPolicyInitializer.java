package ffdd.opsconsole.team.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Adds the first editable F1 Rank How document for a fresh development database.
 * Existing PC-authored content is never replaced.
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevelopmentRankHowPolicyInitializer implements ApplicationRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PlatformConfigFacade config;

    @Override
    public void run(ApplicationArguments args) {
        config.insertAdminValueIfMissing(
                PublishedRankHowPolicyService.CONFIG_KEY,
                serialize(defaultDocument()),
                "JSON",
                "published_content",
                "F1 development baseline; editable and publishable from PC V-Rank");
    }

    private Map<String, Object> defaultDocument() {
        return Map.of(
                "version", "2026.08.22",
                "status", "PUBLISHED",
                "revision", 1,
                "locales", Map.of(
                        "zh", policy(
                                "V-Rank 由真实购买、有效直推、团队业绩和下级等级共同决定。",
                                "等级如何晋升", "达到 PC 管理端 F1 已发布的全部正数门槛后，服务端按阶逐级晋升。",
                                "奖励如何发放", "等级权益、票权和培育奖励以服务端结算与佣金事件为准。"),
                        "en", policy(
                                "V-Rank is calculated from verified purchases, active referrals, team volume and qualified legs.",
                                "How promotion works", "The server promotes one step at a time after every positive F1 threshold is met.",
                                "How rewards are paid", "Benefits, votes and cultivation rewards follow server settlement and commission events."),
                        "vi", policy(
                                "V-Rank dựa trên giao dịch hợp lệ, tuyến giới thiệu hoạt động, doanh số đội nhóm và nhánh đạt chuẩn.",
                                "Cách thăng hạng", "Máy chủ thăng từng bậc sau khi đáp ứng toàn bộ ngưỡng dương đã công bố trong F1.",
                                "Cách trả thưởng", "Quyền lợi, phiếu bầu và thưởng đào tạo dựa trên quyết toán và sự kiện hoa hồng của máy chủ.")));
    }

    private Map<String, Object> policy(String hero, String firstTitle, String firstBody,
                                       String secondTitle, String secondBody) {
        return Map.of(
                "hero", hero,
                "sections", List.of(
                        Map.of("id", "promotion", "title", firstTitle, "body", firstBody, "order", 1),
                        Map.of("id", "rewards", "title", secondTitle, "body", secondBody, "order", 2)));
    }

    private String serialize(Map<String, Object> document) {
        try {
            return JSON.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("RANK_HOW_DEVELOPMENT_BASELINE_INVALID", exception);
        }
    }
}
