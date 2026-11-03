package ffdd.opsconsole.content.domain;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import ffdd.opsconsole.shared.i18n.InterfaceLanguages;

public record AppTrustSectionsView(
        boolean serverCanonical,
        String source,
        String sourceEnvironment,
        String runId,
        List<Section> sections) {
    public AppTrustSectionsView(List<Section> sections) {
        this(true, "nx_trust_section_version:published", "PRODUCTION", "", sections);
    }
    public AppTrustSectionsView forLanguage(String locale) {
        String code = InterfaceLanguages.baseCode(locale);
        if (!InterfaceLanguages.isAdditional(code)) return this;
        List<Section> resolved = sections.stream().map(section -> {
            List<Field> fields = new ArrayList<>(section.fields());
            Set<String> keys = fields.stream().map(Field::key).collect(Collectors.toSet());
            for (Field field : section.fields()) {
                if (!field.key().matches(".+[._-]en")) continue;
                String target = field.key().substring(0, field.key().length() - 2) + code;
                if (!keys.contains(target)) fields.add(new Field(target, field.label(), field.value()));
            }
            return new Section(section.sectionKey(), section.version(), section.description(), section.structure(), fields);
        }).toList();
        return new AppTrustSectionsView(serverCanonical, source, sourceEnvironment, runId, resolved);
    }

    public record Section(
            String sectionKey,
            String version,
            String description,
            String structure,
            List<Field> fields) {
    }

    public record Field(String key, String label, String value) {
    }
}
