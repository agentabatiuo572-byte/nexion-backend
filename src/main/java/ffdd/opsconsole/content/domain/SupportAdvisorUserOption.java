package ffdd.opsconsole.content.domain;

/** Minimal identity needed to choose a dedicated-support binding; no balances or risk details. */
public record SupportAdvisorUserOption(Long userId, String userNo, String nickname, String phoneMasked) {
}
