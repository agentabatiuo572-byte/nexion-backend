package ffdd.opsconsole.finance.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/** Internal evidence only. The caller authorizes all IDs and owns the repeatable-read snapshot. */
public interface SupportFundsReadFacade extends DomainFacade {
    Snapshot readCurrent(Collection<Long> customerIds);

    enum ReadState { READY, PARTIAL, UNKNOWN, FAILED }
    enum EvidenceState { READY, UNKNOWN }
    enum WithdrawalState { SUCCESS, PROCESSING, NOT_SUCCESSFUL, HELD, RECOVERY, UNKNOWN }
    /** The withdrawal source has no historical environment or event ownership certificate. */
    enum CoverageState { UNKNOWN }
    enum Reason {
        WALLET_MISSING, WALLET_READ_FAILED, WALLET_IDENTITY_UNVERIFIED,
        USDT_BALANCE_UNVERIFIED, NEX_BALANCE_UNVERIFIED, SOURCE_OBSERVATION_UNVERIFIED,
        WITHDRAWAL_READ_FAILED, UNKNOWN_WITHDRAWAL_STATUS, WITHDRAWAL_PRINCIPAL_UNVERIFIED,
        WITHDRAWAL_SETTLEMENT_UNVERIFIED, WITHDRAWAL_TIME_UNVERIFIED
    }

    record Snapshot(List<Long> customerIds,WalletRead wallets,WithdrawalRead withdrawals,String businessZone) {
        public Snapshot {customerIds=List.copyOf(customerIds);}
    }
    /** One evidence row for every requested customer. A missing wallet retains null amounts. */
    record WalletRead(List<WalletEvidence> rows,ReadState state,List<Reason> reasons,LocalDateTime evaluatedDbAt) {
        public WalletRead {rows=List.copyOf(rows);reasons=List.copyOf(reasons);}
    }
    record WalletEvidence(long customerId,Long walletId,Long version,BigDecimal usdtAvailable,
            BigDecimal nexAvailable,LocalDateTime updatedAt,EvidenceState state,List<Reason> reasons) {
        public WalletEvidence {reasons=List.copyOf(reasons);}
    }
    /** Successful raw row observation is not complete history, production environment, or saved group attribution. */
    record WithdrawalRead(List<WithdrawalEvidence> rows,ReadState state,List<Reason> reasons,
            LocalDateTime evaluatedDbAt,CoverageState historicalEnvironmentStatus,CoverageState eventOwnershipStatus) {
        public WithdrawalRead {rows=List.copyOf(rows);reasons=List.copyOf(reasons);}
    }
    /** Raw principal/actual fee/net stay in their source currency; no address, chain transaction, or person data. */
    record WithdrawalEvidence(long withdrawalId,long customerId,String currency,BigDecimal principal,
            BigDecimal actualFee,BigDecimal net,String status,String canonicalStatus,WithdrawalState state,
            LocalDateTime completedAt,LocalDateTime updatedAt,List<Reason> reasons) {
        public WithdrawalEvidence {reasons=List.copyOf(reasons);}
    }
}
