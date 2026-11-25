package ffdd.opsconsole.common.boundary;

/** Capacity operations for the bound phone, inside the caller's account transaction. */
public interface PhoneActivationCapacityContract extends DomainFacade {
    void requireCapacity(Long userId, Long currentBoundPhoneDeviceId);
    void releaseBoundPhoneSlot(long userId, long deviceId);
}
