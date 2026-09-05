package ffdd.opsconsole.growth.application;

import ffdd.opsconsole.growth.domain.PublicSponsorPreviewView;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.growth.mapper.PublicSponsorPreviewMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.UserAuthEnvironment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PublicSponsorPreviewService {
    private final PublicSponsorPreviewMapper mapper;
    private final OpsReferralRewardService rewards;
    private final Environment environment;

    public ApiResult<PublicSponsorPreviewView> preview(String rawCode) {
        String code = canonicalCode(rawCode);
        if (!code.matches("[A-Z0-9]{4,32}")) {
            return ApiResult.fail(422, "REFERRAL_PREVIEW_INVALID");
        }
        UserAuthEnvironment audience = UserAuthEnvironment.resolve(environment).orElse(null);
        if (audience == null) return ApiResult.fail(503, "REFERRAL_PREVIEW_UNAVAILABLE");
        List<PublicSponsorPreviewMapper.SponsorRow> candidates = mapper.findActiveByCanonicalCode(code);
        if (candidates == null || candidates.size() != 1
                || !audience.acceptsSandbox(candidates.get(0).sandbox())) {
            return ApiResult.fail(404, "REFERRAL_PREVIEW_NOT_FOUND");
        }
        PublicSponsorPreviewMapper.SponsorRow row = candidates.get(0);
        try {
            ReferralRewardPublicConfigView config = rewards.publicConfig();
            if (config == null || config.welcomeGift() == null
                    || config.welcomeGift().usdtAmount() == null
                    || config.welcomeGift().nexAmount() == null) {
                return ApiResult.fail(503, "REFERRAL_PREVIEW_UNAVAILABLE");
            }
            return ApiResult.ok(new PublicSponsorPreviewView(
                    code,
                    audience.name(),
                    new PublicSponsorPreviewView.Sponsor(
                            maskName(row.nickname()), safe(row.vRank(), "V0")),
                    new PublicSponsorPreviewView.Gift(
                            config.enabled() ? "PENDING_REVIEW" : "DISABLED",
                            config.welcomeGift().usdtAmount(), config.welcomeGift().nexAmount())));
        } catch (RuntimeException exception) {
            return ApiResult.fail(503, "REFERRAL_PREVIEW_UNAVAILABLE");
        }
    }

    static String canonicalCode(String value) {
        return value == null ? "" : value.trim().replace("-", "").toUpperCase(Locale.ROOT);
    }

    static String maskName(String value) {
        if (value == null || value.isBlank()) return "N•••";
        int first = value.codePointAt(0);
        return new String(Character.toChars(first)) + "•••";
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
