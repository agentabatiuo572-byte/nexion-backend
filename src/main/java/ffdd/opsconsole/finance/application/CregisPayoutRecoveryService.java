package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.finance.cregis.CregisConstants;
import ffdd.opsconsole.finance.cregis.CregisGateway;
import ffdd.opsconsole.finance.cregis.CregisGatewayException;
import ffdd.opsconsole.finance.cregis.CregisGatewayRouter;
import ffdd.opsconsole.finance.mapper.WithdrawalPayoutMapper;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Recover the original submission identity; settlement remains with the chain observer. */
@Service
@RequiredArgsConstructor
public class CregisPayoutRecoveryService {
    private final WithdrawalPayoutMapper mapper;
    private final CregisGatewayRouter router;
    private final WithdrawalPayoutFinalizer finalizer;
    private final AdminIdempotencyService idempotency;
    private final AdminOperatorRoleResolver admins;

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Detail detail(String withdrawalNo) {
        requireAdmin(false);
        var row = mapper.payout(withdrawalNo);
        if (row == null || !"USDT-BEP20".equals(row.chain())) throw new BizException(404, "WITHDRAWAL_NOT_FOUND");
        return new Detail(row.withdrawalNo(), row.payoutSource(),
                row.providerCid() == null ? null : row.providerCid().toString(), row.status(),
                "provider".equals(row.payoutSource()) && "TX_ORPHANED".equals(row.status())
                        && row.providerIdempotencyKey() != null && !row.providerIdempotencyKey().isBlank());
    }

    public Result recover(String withdrawalNo, String key, String providerCid, String reason) {
        long adminId = requireAdmin(true);
        if (withdrawalNo == null || !withdrawalNo.matches("WD-[A-Za-z0-9-]{1,60}")
                || key == null || key.isBlank() || key.length() > 128
                || providerCid == null || !providerCid.matches("[1-9][0-9]{0,18}")
                || reason == null || reason.trim().length() < 10 || reason.length() > 300)
            throw new BizException(422, "CREGIS_PAYOUT_RECOVERY_REQUEST_INVALID");
        long cid;
        try { cid = Long.parseLong(providerCid); }
        catch (NumberFormatException invalid) { throw new BizException(422, "CREGIS_PAYOUT_RECOVERY_REQUEST_INVALID"); }
        String hash = sha(withdrawalNo + "|" + cid + "|" + reason.trim());
        String scope = "CREGIS_PAYOUT_RECOVERY:" + adminId;
        var receipt = idempotency.recoveryResult(scope, key, hash, Result.class);
        if (receipt.status() == AdminIdempotencyService.RecoveryStatus.SUCCEEDED) return receipt.response();
        if (receipt.status() != AdminIdempotencyService.RecoveryStatus.NOT_FOUND
                && receipt.status() != AdminIdempotencyService.RecoveryStatus.FAILED)
            throw new BizException(409, "CREGIS_PAYOUT_RECOVERY_IN_PROGRESS_OR_CONFLICT");
        var row = mapper.payout(withdrawalNo);
        if (row == null) throw new BizException(404, "WITHDRAWAL_NOT_FOUND");
        if (!"USDT-BEP20".equals(row.chain()) || !"provider".equals(row.payoutSource())
                || row.providerIdempotencyKey() == null || row.providerIdempotencyKey().isBlank()
                || (row.providerCid() != null && row.providerCid() != cid)
                || !("TX_ORPHANED".equals(row.status()) || row.providerCid() != null
                    && java.util.Set.of("SENT", "CONFIRMED", "FAILED").contains(row.status())))
            throw new BizException(409, "CREGIS_PAYOUT_RECOVERY_STATE_CONFLICT");
        CregisGateway.PayoutOrder observed;
        try {
            // HttpCregisGateway also validates the configured project ID in the independent response.
            observed = router.provider().queryPayout(new CregisGateway.PayoutQuery(
                    cid, row.providerIdempotencyKey(), row.targetAddress(), row.netReceive()));
        } catch (CregisGatewayException failure) {
            if (failure.kind() == CregisGatewayException.Kind.INVALID_RESPONSE
                    || failure.kind() == CregisGatewayException.Kind.CONFLICT)
                throw new BizException(409, "CREGIS_PAYOUT_RECOVERY_EVIDENCE_CONFLICT");
            throw new BizException(503, "CREGIS_PAYOUT_RECOVERY_QUERY_UNAVAILABLE");
        }
        if (observed == null || observed.cid() != cid || observed.status() == null
                || !CregisConstants.BSC_CHAIN_ID.equals(observed.chainId())
                || !CregisConstants.USDT_BEP20_TOKEN_ID.equalsIgnoreCase(observed.tokenId())
                || !CregisConstants.USDT_BEP20_CURRENCY.equalsIgnoreCase(observed.currency())
                || !row.providerIdempotencyKey().equals(observed.thirdPartyId())
                || !row.targetAddress().equalsIgnoreCase(observed.address()) || observed.amount() == null
                || row.netReceive() == null || row.netReceive().compareTo(observed.amount()) != 0)
            throw new BizException(409, "CREGIS_PAYOUT_RECOVERY_EVIDENCE_CONFLICT");
        String actor = AdminActorResolver.resolve(null);
        return idempotency.executeRetained(scope, key, hash, Result.class, () -> new Result(
                withdrawalNo, Long.toString(cid), finalizer.recoverProviderSubmission(row, cid, actor, reason.trim())));
    }

    private long requireAdmin(boolean write) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"ADMIN".equals(details.get("subjectType"))
                || !(auth.getPrincipal() instanceof String id) || !id.matches("[1-9][0-9]{0,18}")
                || !auth.getAuthorities().stream().anyMatch(a -> "finance_d2_read".equals(a.getAuthority()))
                || write && !auth.getAuthorities().stream().anyMatch(a -> "finance_d2_withdrawal_approve".equals(a.getAuthority())))
            throw new BizException(403, "ADMIN_FINANCE_AUTH_REQUIRED");
        long adminId;
        try { adminId = Long.parseLong(id); }
        catch (NumberFormatException invalid) { throw new BizException(403, "ADMIN_FINANCE_AUTH_REQUIRED"); }
        if (!admins.isActiveAdministrator(adminId))
            throw new BizException(403, "ADMIN_FINANCE_AUTH_REQUIRED");
        return adminId;
    }

    private String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record Result(String withdrawalNo, String providerCid, String state) {}
    public record Detail(String withdrawalNo, String source, String providerCid, String state, boolean canRecover) {}
}
