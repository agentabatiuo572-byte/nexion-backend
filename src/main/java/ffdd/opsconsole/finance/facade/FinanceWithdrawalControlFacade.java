package ffdd.opsconsole.finance.facade;

public interface FinanceWithdrawalControlFacade {
    int freezePendingWithdrawalsForUser(Long userId, String reason, String operator);

    int restoreWithdrawalsFrozenByUserStatus(Long userId, String reason, String operator);
}
