package ffdd.opsconsole.growth.domain;

import java.math.BigDecimal;

/** Minimal unauthenticated referral preview. No user id, phone or raw nickname is exposed. */
public record PublicSponsorPreviewView(
        String code,
        String sourceEnvironment,
        String kind,
        Sponsor sponsor,
        Gift gift) {

    public PublicSponsorPreviewView(String code, String sourceEnvironment, Sponsor sponsor, Gift gift) {
        this(code, sourceEnvironment, "USER", sponsor, gift);
    }

    public record Sponsor(String displayName, String vRank) {
    }

    public record Gift(String status, BigDecimal usdtAmount, BigDecimal nexAmount) {
    }
}
