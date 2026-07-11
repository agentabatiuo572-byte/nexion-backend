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
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.w3c.dom.Element;

class OpsConsoleArchitectureTest {

    private static final Path MAIN_JAVA_ROOT = Path.of("src", "main", "java");
    private static final Path MAIN_RESOURCES_ROOT = Path.of("src", "main", "resources");
    private static final Path SCRIPTS_ROOT = Path.of("scripts");
    private static final Pattern FIELD_INJECTION_PATTERN =
            Pattern.compile("@(?:Autowired|Resource|Inject)\\b");
    private static final Pattern SPRING_BEAN_PATTERN =
            Pattern.compile("@(?:RestController|Controller|RestControllerAdvice|ControllerAdvice|Service|Component|Repository|Configuration|ApplicationService)\\b");
    private static final Pattern FINAL_INSTANCE_FIELD_PATTERN =
            Pattern.compile("(?m)^\\s*private\\s+final\\s+[^=;]+\\s+[A-Za-z0-9_]+\\s*;");
    private static final Pattern NON_FINAL_INSTANCE_FIELD_PATTERN =
            Pattern.compile("(?m)^\\s*private\\s+(?!static\\b)(?!final\\b)[^\\r\\n=;()]+\\s+[A-Za-z0-9_]+\\s*(?:=|;)");
    private static final Pattern SIMPLE_CONFIG_FINAL_FIELD_PATTERN =
            Pattern.compile("^\\s*private\\s+final\\s+(?:String|boolean|Boolean|int|Integer|long|Long|BigDecimal)\\s+[A-Za-z0-9_]+\\s*;");
    private static final Pattern REQUIRED_ARGS_CONSTRUCTOR_PATTERN =
            Pattern.compile("@RequiredArgsConstructor\\b");
    private static final Pattern CONFIGURATION_PROPERTIES_PATTERN =
            Pattern.compile("@ConfigurationProperties\\b");
    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("\\b(?:class|record)\\s+([A-Za-z0-9_]+)");
    private static final Pattern HAND_WRITTEN_JDBC_PATTERN =
            Pattern.compile("\\b(?:JdbcTemplate|NamedParameterJdbcTemplate|SimpleJdbcInsert)\\b|org\\.springframework\\.jdbc");
    private static final Pattern LEGACY_ADMIN_ROUTE_PATTERN =
            Pattern.compile("/auth/admin\\b|/api/config\\b");
    private static final Pattern LEGACY_DISTRIBUTED_PERMISSION_PATTERN =
            Pattern.compile("(?m)^.*PERM_[A-Z0-9_]+.*'/(?:auth/admins|auth/access-control|bff|compute|commerce|genesis|wallet|earnings|team|notifications|missions|compliance|openapi)(?:/|\\*|').*$");
    private static final Pattern LOMBOK_VALUE_COPYABLE_PATTERN =
            Pattern.compile("(?m)^\\s*lombok\\.copyableAnnotations\\s*\\+=\\s*org\\.springframework\\.beans\\.factory\\.annotation\\.Value\\s*$");
    private static final Pattern MYBATIS_TEXT_BLOCK_ANNOTATION_PATTERN =
            Pattern.compile("@(?:Select|Insert|Update|Delete)\\(\\\"\\\"\\\"([\\s\\S]*?)\\\"\\\"\\\"\\)");
    private static final Pattern RAW_XML_UNSAFE_LESS_THAN_OPERATOR_PATTERN =
            Pattern.compile("(?m)\\s<=?\\s");
    private static final Pattern XML_ENTITY_OPERATOR_PATTERN =
            Pattern.compile("&(?:lt|gt);");

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
    void backendDoesNotExposeLegacyAdminRoutePrefixes() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Path file : javaAndResourceFiles()) {
            String source = Files.readString(file);
            if (LEGACY_ADMIN_ROUTE_PATTERN.matcher(source).find()) {
                violations.add(displayPath(file));
            }
        }

        assertThat(violations)
                .as("Back-office routes must use /api/admin/** only; do not reintroduce /auth/admin or /api/config")
                .isEmpty();
    }

    @Test
    void sqlSeedPermissionsDoNotPointToRemovedDistributedApiPaths() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Path file : scriptFiles()) {
            String source = Files.readString(file);
            if (LEGACY_DISTRIBUTED_PERMISSION_PATTERN.matcher(source).find()) {
                violations.add(displayPath(file));
            }
        }

        assertThat(violations)
                .as("Seeded admin API permissions must not point to removed distributed-service route prefixes")
                .isEmpty();
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
                        "OPERATOR_REQUIRED",
                        "IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void applicationUsesSingleBootAppAndMybatisMapperScan() {
        assertThat(NexionOpsConsoleApplication.class.getAnnotation(SpringBootApplication.class)).isNotNull();
        MapperScan mapperScan = NexionOpsConsoleApplication.class.getAnnotation(MapperScan.class);

        assertThat(mapperScan.value()).containsExactly("ffdd.opsconsole.**.mapper");
    }

    @Test
    void springBeansUseRequiredArgsConstructorInjectionOnly() throws Exception {
        List<String> fieldInjectionViolations = new ArrayList<>();
        List<String> mutableBeanFieldViolations = new ArrayList<>();
        List<String> constructorInjectionViolations = new ArrayList<>();
        List<String> explicitConstructorViolations = new ArrayList<>();

        for (Path file : sourceFiles()) {
            String source = Files.readString(file);
            if (FIELD_INJECTION_PATTERN.matcher(source).find()) {
                fieldInjectionViolations.add(displayPath(file));
            }
            if (!SPRING_BEAN_PATTERN.matcher(source).find()) {
                continue;
            }
            if (!CONFIGURATION_PROPERTIES_PATTERN.matcher(source).find()
                    && NON_FINAL_INSTANCE_FIELD_PATTERN.matcher(source).find()) {
                mutableBeanFieldViolations.add(displayPath(file));
            }
            if (!FINAL_INSTANCE_FIELD_PATTERN.matcher(source).find()) {
                continue;
            }

            String className = className(source);
            boolean hasRequiredArgsConstructor = REQUIRED_ARGS_CONSTRUCTOR_PATTERN.matcher(source).find();
            boolean hasExplicitConstructor = className != null
                    && Pattern.compile("(?m)^\\s*(?:public|protected|private)?\\s*"
                                    + Pattern.quote(className)
                                    + "\\s*\\(")
                            .matcher(source)
                            .find();
            if (hasExplicitConstructor) {
                explicitConstructorViolations.add(displayPath(file));
            }

            if (!hasRequiredArgsConstructor || hasExplicitConstructor) {
                constructorInjectionViolations.add(displayPath(file)
                        + " requiredArgs=" + hasRequiredArgsConstructor
                        + " explicitConstructor=" + hasExplicitConstructor);
            }
        }

        assertThat(fieldInjectionViolations)
                .as("Spring Bean dependencies must not use @Autowired/@Resource/@Inject field injection")
                .isEmpty();
        assertThat(mutableBeanFieldViolations)
                .as("Spring Beans must keep dependencies as private final fields; mutable fields are allowed only on @ConfigurationProperties binders")
                .isEmpty();
        assertThat(constructorInjectionViolations)
                .as("Spring Bean dependencies must be final fields injected by Lombok @RequiredArgsConstructor")
                .isEmpty();
        assertThat(explicitConstructorViolations)
                .as("Spring Beans must not hand-write dependency constructors; use Lombok @RequiredArgsConstructor")
                .isEmpty();
    }

    @Test
    void springBeanSimpleFinalConfigFieldsDeclareValueInjection() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Path file : sourceFiles()) {
            String source = Files.readString(file);
            if (!SPRING_BEAN_PATTERN.matcher(source).find()) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (!SIMPLE_CONFIG_FINAL_FIELD_PATTERN.matcher(line).find()) {
                    continue;
                }
                String previous = previousNonBlank(lines, index);
                if (!line.contains("@Value(")
                        && !previous.contains("@Value(")
                        && !previous.contains("@SuppressWarnings(\"ArchitectureConfigField\")")) {
                    violations.add(displayPath(file) + ":" + (index + 1) + " " + line.trim());
                }
            }
        }

        assertThat(violations)
                .as("Spring Bean simple final config values must carry @Value when Lombok generates constructors")
                .isEmpty();
    }

    @Test
    void lombokConstructorInjectionCopiesSpringValueAnnotations() throws Exception {
        Path lombokConfig = Path.of("lombok.config");

        assertThat(lombokConfig)
                .as("lombok.config must exist so @Value final fields work with @RequiredArgsConstructor")
                .exists();
        assertThat(LOMBOK_VALUE_COPYABLE_PATTERN.matcher(Files.readString(lombokConfig)).find())
                .as("Spring @Value must be copied onto Lombok-generated constructor parameters")
                .isTrue();
    }

    @Test
    void mappersExtendMybatisPlusBaseMapperAndNoHandWrittenJdbcRemains() throws Exception {
        List<Path> mapperFiles = sourceFiles().stream()
                .filter(path -> displayPath(path).contains("/mapper/"))
                .filter(path -> path.getFileName().toString().endsWith("Mapper.java"))
                .toList();
        List<String> mapperViolations = new ArrayList<>();
        List<String> jdbcViolations = new ArrayList<>();

        for (Path file : sourceFiles()) {
            String source = Files.readString(file);
            if (HAND_WRITTEN_JDBC_PATTERN.matcher(source).find()) {
                jdbcViolations.add(displayPath(file));
            }
        }
        for (Path mapperFile : mapperFiles) {
            String source = Files.readString(mapperFile);
            if (!source.contains("extends BaseMapper<")
                    && !source.contains("@SuppressWarnings(\"MybatisPlusBaseMapper\")")) {
                mapperViolations.add(displayPath(mapperFile));
            }
        }

        assertThat(mapperFiles).hasSizeGreaterThanOrEqualTo(12);
        assertThat(mapperViolations)
                .as("MyBatis-Plus mapper interfaces must extend BaseMapper")
                .isEmpty();
        assertThat(jdbcViolations)
                .as("Application code must not reintroduce hand-written Spring JDBC repositories")
                .isEmpty();
    }

    @Test
    void mybatisAnnotationSqlUsesJdbcOperatorsExceptInsideDynamicXmlScripts() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Path file : sourceFiles()) {
            String source = Files.readString(file);
            java.util.regex.Matcher matcher = MYBATIS_TEXT_BLOCK_ANNOTATION_PATTERN.matcher(source);
            while (matcher.find()) {
                String sql = matcher.group(1);
                boolean dynamicXmlScript = sql.contains("<script>");
                if (dynamicXmlScript && RAW_XML_UNSAFE_LESS_THAN_OPERATOR_PATTERN.matcher(sql).find()) {
                    violations.add(displayPath(file) + " uses raw less-than operators inside MyBatis XML script SQL");
                }
                if (!dynamicXmlScript && XML_ENTITY_OPERATOR_PATTERN.matcher(sql).find()) {
                    violations.add(displayPath(file) + " uses XML entity operators in plain JDBC annotation SQL");
                }
            }
        }

        assertThat(violations)
                .as("MyBatis annotation SQL must use XML entities only inside <script> dynamic SQL; plain annotation SQL is sent to JDBC as-is")
                .isEmpty();
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

    private static List<Path> sourceFiles() throws Exception {
        try (Stream<Path> files = Files.walk(MAIN_JAVA_ROOT)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java")).toList();
        }
    }

    private static List<Path> javaAndResourceFiles() throws Exception {
        List<Path> files = new ArrayList<>(sourceFiles());
        if (Files.exists(MAIN_RESOURCES_ROOT)) {
            try (Stream<Path> resourceFiles = Files.walk(MAIN_RESOURCES_ROOT)) {
                files.addAll(resourceFiles
                        .filter(Files::isRegularFile)
                        .filter(path -> !path.getFileName().toString().endsWith(".class"))
                        .toList());
            }
        }
        return files;
    }

    private static List<Path> scriptFiles() throws Exception {
        if (!Files.exists(SCRIPTS_ROOT)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(SCRIPTS_ROOT)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList();
        }
    }

    private static String className(String source) {
        java.util.regex.Matcher matcher = CLASS_NAME_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String displayPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String previousNonBlank(List<String> lines, int index) {
        for (int current = index - 1; current >= 0; current--) {
            String line = lines.get(current).trim();
            if (!line.isEmpty()) {
                return line;
            }
        }
        return "";
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
