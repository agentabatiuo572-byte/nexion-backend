package ffdd.opsconsole.growth.facade;

/** H7 voucher ownership boundary used by other domains. */
public interface VoucherGrantFacade {

    VoucherGrantResult grant(VoucherGrantCommand command);

    record VoucherGrantCommand(
            Long userId,
            String voucherId,
            String grantKey,
            String sourceType,
            String sourceId,
            String operator,
            String reason) {
    }

    record VoucherGrantResult(String grantId, boolean replayed) {
    }
}
