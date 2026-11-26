package ffdd.opsconsole.risk.domain;

public enum RiskSignalCategory {
    MULTI_ACCOUNT("多账户风险"),
    ABUSE("套利或刷量风险"),
    WITHDRAW("提现风险"),
    TAMPER("篡改风险"),
    USER_FROZEN("账户冻结"),
    CHARGEBACK_SHORTFALL("拒付追回有差额"),
    OTHER("其他风险信号");

    private final String displayName;

    RiskSignalCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
