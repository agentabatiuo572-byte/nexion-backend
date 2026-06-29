package ffdd.opsconsole.user.facade;

public interface UserKycStatusFacade {
    boolean updateKycStatusByUserNo(String userNo, String kycStatus, String reason, String operator);
}
