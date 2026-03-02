package com.example.epub.api;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureTest {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "domain", Set.of(),
            "application", Set.of("domain"),
            "infrastructure", Set.of("application", "domain", "migrations"),
            "migrations", Set.of(),
            "api", Set.of("application", "infrastructure", "domain", "migrations"),
            "worker", Set.of("application", "infrastructure", "domain", "migrations"));

    @Test
    void productionBytecodeHasNoReverseDependenciesOrPackageCycles() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.epub");
        assertTrue(classes.stream().anyMatch(type -> type.getName().equals(ApiApplication.class.getName())));
        assertTrue(classes.stream().anyMatch(type -> type.getName().equals("com.example.epub.worker.WorkerApplication")));
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                        "javax.persistence..", "..infrastructure..", "..api..", "..worker..")
                .allowEmptyShould(true).check(classes);
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..worker..")
                .check(classes);
        noClasses().that().resideInAPackage("..worker..")
                .should().dependOnClassesThat().resideInAPackage("..api..")
                .check(classes);
        slices().matching("com.example.epub.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void productionMavenGraphEnforcesBoundariesEvenBeforeDomainClassesExist() throws Exception {
        Path root = reactorRoot();
        Map<String, Set<String>> graph = new HashMap<>();
        for (String module : ALLOWED.keySet()) {
            Element pom = parse(root.resolve(module).resolve("pom.xml"));
            Set<String> internal = new HashSet<>();
            var sections = pom.getElementsByTagName("dependencies");
            for (int section = 0; section < sections.getLength(); section++) {
                if (sections.item(section).getParentNode() != pom) continue;
                var dependencies = ((Element) sections.item(section)).getElementsByTagName("dependency");
                for (int i = 0; i < dependencies.getLength(); i++) {
                    Element dependency = (Element) dependencies.item(i);
                    if ("test".equals(value(dependency, "scope"))) continue;
                    String group = value(dependency, "groupId");
                    String artifact = value(dependency, "artifactId");
                    if ("com.example.epub".equals(group)) {
                        internal.add(artifact);
                    } else if (Set.of("domain", "application", "migrations").contains(module)) {
                        fail(module + " must remain free of external production dependencies: " + group + ":" + artifact);
                    }
                    if ("worker".equals(module)) {
                        assertFalse(artifact.contains("web") || artifact.contains("tomcat") || artifact.contains("onnx")
                                || artifact.contains("sherpa"), "Headless foundation worker must not load " + artifact);
                    }
                }
            }
            graph.put(module, internal);
        }
        validateGraph(graph);
        // Check the declared reactor too: no accidental modules can evade these rules.
        var modules = parse(root.resolve("pom.xml")).getElementsByTagName("module");
        Set<String> declared = new HashSet<>();
        for (int i = 0; i < modules.getLength(); i++) declared.add(modules.item(i).getTextContent().trim());
        assertEquals(ALLOWED.keySet(), declared);
    }

    @Test
    void guardRejectsReverseEdgesAndCycles() {
        assertThrows(AssertionError.class, () -> validateGraph(Map.of("domain", Set.of("api"))));
        assertThrows(AssertionError.class, () -> validateGraph(Map.of("worker", Set.of("api"))));
        assertThrows(AssertionError.class, () -> visit("a", Map.of("a", Set.of("b"), "b", Set.of("a")), new HashSet<>(), new HashSet<>()));
    }

    private static void validateGraph(Map<String, Set<String>> graph) {
        graph.forEach((module, dependencies) -> assertTrue(ALLOWED.get(module).containsAll(dependencies),
                module + " contains forbidden dependencies: " + dependencies));
        Set<String> visited = new HashSet<>();
        graph.keySet().forEach(module -> visit(module, graph, new HashSet<>(), visited));
    }

    private static void visit(String module, Map<String, Set<String>> graph, Set<String> path, Set<String> visited) {
        assertTrue(path.add(module), "Module dependency cycle at " + module);
        if (visited.add(module)) {
            for (String dependency : graph.getOrDefault(module, Set.of())) visit(dependency, graph, path, visited);
        }
        path.remove(module);
    }

    private static Path reactorRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("domain/pom.xml"))) path = path.getParent();
        assertNotNull(path, "Run tests from a checkout of the Maven reactor");
        return path;
    }

    private static Element parse(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }

    private static String value(Element element, String name) {
        var nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
