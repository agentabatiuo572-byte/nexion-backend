package ffdd.opsconsole.risk.facade;

public interface RiskUserStateFacade {
    void recordUserFrozen(Long userId, String userNo, String reason, String operator);
}
