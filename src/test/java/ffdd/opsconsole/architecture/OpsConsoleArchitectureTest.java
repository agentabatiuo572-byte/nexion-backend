package ffdd.opsconsole.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import ffdd.opsconsole.NexionOpsConsoleApplication;
import ffdd.opsconsole.common.api.OpsAdminApi;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.DomainDependencyPolicy;
import ffdd.opsconsole.common.boundary.SourceLayer;
import ffdd.opsconsole.common.boundary.TargetLayer;
import ffdd.opsconsole.common.domain.DomainCode;
import ffdd.opsconsole.common.domain.OpsDomain;
import ffdd.opsconsole.common.domain.OpsDomainCatalog;
import ffdd.opsconsole.shared.audit.AuditLogController;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.w3c.dom.Element;

class OpsConsoleArchitectureTest {

    @Test
    void domainCatalogContainsTwelveOpsConsoleDomains() {
        assertThat(OpsDomainCatalog.activeDomains()).hasSize(12);

        Set<DomainCode> codes = OpsDomainCatalog.activeDomains().stream()
                .map(domain -> domain.code())
                .collect(Collectors.toSet());

        assertThat(codes).containsExactlyInAnyOrder(DomainCode.values());
    }

    @Test
    void activeCatalogUsesExpectedApiPrefixesAndKeepsSunsetProductsHistoricalOnly() {
        assertThat(OpsAdminApi.ADMIN_PREFIX).isEqualTo("/api/admin");
        assertThat(OpsAdminApi.CONFIG_PREFIX).isEqualTo("/api/config");
        assertThat(OpsAdminApi.IDEMPOTENCY_KEY_HEADER).isEqualTo("Idempotency-Key");

        assertThat(OpsDomainCatalog.activeDomains())
                .allSatisfy(domain -> assertThat(domain.adminApiPrefix()).startsWith(OpsAdminApi.ADMIN_PREFIX + "/"));
        assertThat(OpsDomainCatalog.deprecatedCapabilities())
                .contains("PremiumSubscription", "NexV2Vault", "PointsReward");
        assertThat(OpsDomainCatalog.activeCapabilityNames())
                .noneMatch(name -> name.contains("PremiumSubscription"))
                .noneMatch(name -> name.contains("NexV2Vault"))
                .noneMatch(name -> name.contains("PointsReward"));
    }

    @Test
    void updateLogCorrectionsAreMappedToOwnerDomains() {
        assertThat(OpsDomainCatalog.updateCorrection("I9_CROSS_AGENT_TRANSFER").owner()).isEqualTo(DomainCode.I);
        assertThat(OpsDomainCatalog.updateCorrection("E3B_DEVICE_RESTORE").owner()).isEqualTo(DomainCode.E);
        assertThat(OpsDomainCatalog.updateCorrection("G3_WEEKLY_CURVE").owner()).isEqualTo(DomainCode.G);
        assertThat(OpsDomainCatalog.updateCorrection("D5_H1_WITHDRAW_NEX_GATE").owner()).isEqualTo(DomainCode.H);
        assertThat(OpsDomainCatalog.updateCorrection("J1_GATE_SHRINK").owner()).isEqualTo(DomainCode.J);
    }

    @Test
    void packageInfoDeclaresDomainOwnershipForEachDomainPackage() throws Exception {
        for (String packageName : OpsDomainCatalog.activeDomains().stream().map(domain -> domain.packageName()).toList()) {
            Package domainPackage = Class.forName(packageName + ".package-info").getPackage();

            assertThat(domainPackage.getAnnotation(OpsDomain.class))
                    .as(packageName)
                    .isNotNull();
        }
    }

    @Test
    void dependencyPolicyMatchesModularMonolithBoundaryRules() {
        assertThat(DomainDependencyPolicy.isAllowed(SourceLayer.WEB, TargetLayer.APPLICATION)).isTrue();
        assertThat(DomainDependencyPolicy.isAllowed(SourceLayer.APPLICATION, TargetLayer.FACADE)).isTrue();
        assertThat(DomainDependencyPolicy.isAllowed(SourceLayer.APPLICATION, TargetLayer.DOMAIN_EVENT)).isTrue();

        assertThat(DomainDependencyPolicy.isAllowed(SourceLayer.WEB, TargetLayer.MAPPER)).isFalse();
        assertThat(DomainDependencyPolicy.isAllowed(SourceLayer.WEB, TargetLayer.ENTITY)).isFalse();
        assertThat(DomainDependencyPolicy.isAllowed(SourceLayer.APPLICATION, TargetLayer.OTHER_DOMAIN_MAPPER)).isFalse();
        assertThat(DomainDependencyPolicy.isAllowed(SourceLayer.APPLICATION, TargetLayer.OTHER_DOMAIN_ENTITY)).isFalse();
    }

    @Test
    void commonErrorCodesExposeCrossCuttingContract() {
        assertThat(Arrays.stream(OpsErrorCode.values()).map(OpsErrorCode::name))
                .contains(
                        "COVERAGE_BELOW_REDLINE",
                        "PHASE_PARAM_READONLY",
                        "INVALID_STATE_TRANSITION",
                        "REASON_REQUIRED",
                        "IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void applicationScansOnlyOpsConsoleButExcludesSharedAuditController() {
        assertThat(NexionOpsConsoleApplication.class.getAnnotation(SpringBootApplication.class)).isNotNull();
        ComponentScan componentScan = NexionOpsConsoleApplication.class.getAnnotation(ComponentScan.class);
        MapperScan mapperScan = NexionOpsConsoleApplication.class.getAnnotation(MapperScan.class);

        assertThat(componentScan.basePackages()).containsExactly("ffdd.opsconsole");
        assertThat(componentScan.excludeFilters()).hasSize(1);
        assertThat(componentScan.excludeFilters()[0].type()).isEqualTo(FilterType.ASSIGNABLE_TYPE);
        assertThat(componentScan.excludeFilters()[0].classes()).containsExactly(AuditLogController.class);
        assertThat(mapperScan.value()).containsExactly("ffdd.opsconsole.**.mapper");
    }

    @Test
    void rootMavenReactorActivatesOnlyMonolithRuntimeModules() throws Exception {
        Element root = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(rootPom().toFile())
                .getDocumentElement();

        assertThat(childrenText(root, "packaging")).containsExactly("jar");
        assertThat(childrenText(root, "modules", "module")).isEmpty();
        assertThat(childrenText(root, "artifactId")).contains("nexion-backend");
    }

    @Test
    void rootProjectOwnsSpringBootBuildPluginDirectly() throws Exception {
        Element root = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(rootPom().toFile())
                .getDocumentElement();

        assertThat(childrenText(root, "plugins", "artifactId")).contains("spring-boot-maven-plugin");
    }

    @Test
    void monolithRootPomDoesNotDependOnRemovedModules() throws Exception {
        Element root = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(rootPom().toFile())
                .getDocumentElement();

        List<String> dependencyArtifactIds = childrenText(root, "dependencies", "artifactId");

        assertThat(dependencyArtifactIds).doesNotContain("nexion-common");
    }

    private static Path rootPom() {
        Path current = Path.of("pom.xml");
        Path parent = Path.of("..", "pom.xml");
        return parent.toFile().isFile() ? parent : current;
    }

    private static List<String> childrenText(Element root, String parentTag, String childTag) {
        Element parent = (Element) root.getElementsByTagName(parentTag).item(0);
        if (parent == null) {
            return List.of();
        }
        return java.util.stream.IntStream.range(0, parent.getElementsByTagName(childTag).getLength())
                .mapToObj(index -> parent.getElementsByTagName(childTag).item(index).getTextContent().trim())
                .toList();
    }

    private static List<String> childrenText(Element root, String childTag) {
        return java.util.stream.IntStream.range(0, root.getElementsByTagName(childTag).getLength())
                .mapToObj(index -> root.getElementsByTagName(childTag).item(index).getTextContent().trim())
                .toList();
    }
}
