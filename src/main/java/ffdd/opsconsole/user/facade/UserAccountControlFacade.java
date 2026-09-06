package ffdd.opsconsole.user.facade;

import java.util.List;

public interface UserAccountControlFacade {
    void lockUsersByUserNos(List<String> userNos);

    int freezeActiveUsersByUserNos(List<String> userNos, String reason, String operator, String sourceRef);

    int restoreUsersFrozenBySource(List<String> userNos, String reason, String operator, String sourceRef);
}
