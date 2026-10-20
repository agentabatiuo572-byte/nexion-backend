package ffdd.opsconsole.common.boundary;

/** Called inside the activation transaction, before creating or reactivating its phone. */
public interface PhoneActivationCapacityContract extends DomainFacade {
    void requireCapacity(Long userId, Long currentBoundPhoneDeviceId);
}
