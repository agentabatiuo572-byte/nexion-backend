package ffdd.opsconsole.growth.web;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.shared.canonical.AppCanonicalBoundaryController;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class AppTrialRouteUniquenessTest {

    @Test
    void trialEligibilityHasExactlyOneHttpOwner() {
        List<String> owners = new ArrayList<>();
        collectGetOwners(AppTrialLifecycleController.class, "/api/trial/eligibility", owners);
        collectGetOwners(AppCanonicalBoundaryController.class, "/api/trial/eligibility", owners);

        assertThat(owners).containsExactly("AppCanonicalBoundaryController#trialEligibility");
    }

    private void collectGetOwners(Class<?> controller, String target, List<String> owners) {
        RequestMapping classMapping = controller.getAnnotation(RequestMapping.class);
        String base = classMapping == null || classMapping.value().length == 0 ? "" : classMapping.value()[0];
        for (Method method : controller.getDeclaredMethods()) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            if (mapping == null) continue;
            Arrays.stream(mapping.value())
                    .map(path -> normalize(base + path))
                    .filter(target::equals)
                    .forEach(path -> owners.add(controller.getSimpleName() + "#" + method.getName()));
        }
    }

    private String normalize(String path) {
        return path.replaceAll("/{2,}", "/");
    }
}
