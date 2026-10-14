package ffdd.opsconsole.auth.facade;

import ffdd.opsconsole.common.boundary.DomainFacade;
import java.util.List;
import java.util.Map;

/** AUTH-owned plain-read evidence, used only inside the caller's finance snapshot. */
public interface SupportFundBirthReadFacade extends DomainFacade {
    Evidence read(List<Long> customerIds);
    record Evidence(List<Map<String,Object>> customers,List<Map<String,Object>> registrationEvents) {
        public Evidence { customers=List.copyOf(customers);registrationEvents=List.copyOf(registrationEvents); }
    }
}
