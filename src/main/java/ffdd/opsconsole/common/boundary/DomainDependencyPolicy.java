package ffdd.opsconsole.common.boundary;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class DomainDependencyPolicy {
    private static final Map<SourceLayer, Set<TargetLayer>> ALLOWED = new EnumMap<>(SourceLayer.class);

    static {
        ALLOWED.put(SourceLayer.WEB, EnumSet.of(TargetLayer.APPLICATION, TargetLayer.DTO));
        ALLOWED.put(SourceLayer.APPLICATION, EnumSet.of(
                TargetLayer.DOMAIN,
                TargetLayer.FACADE,
                TargetLayer.DOMAIN_EVENT,
                TargetLayer.REPOSITORY,
                TargetLayer.DTO));
        ALLOWED.put(SourceLayer.DOMAIN, EnumSet.of(TargetLayer.DOMAIN_EVENT));
        ALLOWED.put(SourceLayer.FACADE, EnumSet.of(TargetLayer.APPLICATION, TargetLayer.DTO));
        ALLOWED.put(SourceLayer.INFRASTRUCTURE, EnumSet.of(TargetLayer.ENTITY, TargetLayer.MAPPER, TargetLayer.REPOSITORY));
    }

    private DomainDependencyPolicy() {
    }

    public static boolean isAllowed(SourceLayer source, TargetLayer target) {
        return ALLOWED.getOrDefault(source, Set.of()).contains(target);
    }
}
