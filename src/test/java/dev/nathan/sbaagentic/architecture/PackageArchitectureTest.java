package dev.nathan.sbaagentic.architecture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

import org.springframework.stereotype.Repository;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class PackageArchitectureTest {

    private static final String BASE = "dev.nathan.sbaagentic";

    /**
     * A module enters this set in the same commit that completes its vertical migration. Legacy
     * packages are deliberately not grandfathered with class-by-class exceptions.
     */
    private static final Set<String> FULLY_MIGRATED_MODULES = Set.of("project", "recording", "workflow");

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(BASE);

    @Test
    void applicationBootstrapStaysAtTheRoot() {
        classes()
                .that().haveSimpleName("SbaAgenticApplication")
                .should().resideInAPackage(BASE)
                .check(classes);
    }

    @Test
    void noNewGlobalLayerOrJunkDrawerPackagesAppear() {
        noClasses().should().resideInAnyPackage(
                        BASE + ".controller..",
                        BASE + ".model..",
                        BASE + ".service..",
                        BASE + ".repository..",
                        BASE + ".common..",
                        BASE + ".shared..",
                        BASE + ".util..")
                .check(classes);
    }

    @Test
    void moduleInternalsNeverLeakAcrossFeatureBoundaries() {
        List<String> violations = classes.stream()
                .flatMap(origin -> origin.getDirectDependenciesFromSelf().stream())
                .filter(this::crossesIntoAnotherModuleInternalPackage)
                .map(Dependency::getDescription)
                .sorted()
                .toList();

        assertThat(violations)
                .as("cross-module imports of ..internal.. packages")
                .isEmpty();
    }

    @Test
    void fullyMigratedModulesFollowInternalLayeringRules() {
        for (String module : FULLY_MIGRATED_MODULES) {
            moduleRules(module).forEach(rule -> rule.check(classes));
        }
    }

    @Test
    void fullyMigratedModuleGraphIsAcyclic() {
        Map<String, Set<String>> graph = new HashMap<>();
        FULLY_MIGRATED_MODULES.forEach(module -> graph.put(module, new HashSet<>()));
        classes.stream()
                .flatMap(origin -> origin.getDirectDependenciesFromSelf().stream())
                .forEach(dependency -> {
                    String origin = moduleOf(dependency.getOriginClass());
                    String target = moduleOf(dependency.getTargetClass());
                    if (origin != null && target != null && !origin.equals(target)
                            && FULLY_MIGRATED_MODULES.contains(origin)
                            && FULLY_MIGRATED_MODULES.contains(target)) {
                        graph.get(origin).add(target);
                    }
                });

        assertThat(findCycle(graph)).as("migrated module dependency cycle").isEmpty();
    }

    private List<ArchRule> moduleRules(String module) {
        String root = BASE + "." + module;
        List<ArchRule> rules = new ArrayList<>();
        rules.add(noClasses()
                .that().resideInAPackage(root + ".internal.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.jdbc..",
                        "jakarta.servlet..",
                        "com.fasterxml.jackson..",
                        "org.elasticsearch..",
                        "java.nio.file..",
                        "java.io..")
                .allowEmptyShould(true));
        rules.add(noClasses()
                .that().resideInAPackage(root + ".internal.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        root + ".internal.adapter.in..",
                        root + ".internal.adapter.out.."));
        rules.add(noClasses()
                .that().resideInAnyPackage(
                        root + ".internal.adapter.in.web..",
                        root + ".internal.adapter.in.mcp..",
                        root + ".internal.adapter.in.cli..")
                .should().dependOnClassesThat().areAnnotatedWith(Repository.class));
        rules.add(classes()
                .that().areAnnotatedWith(Repository.class)
                .and().resideInAPackage(root + "..")
                .should().resideInAPackage(root + ".internal.adapter.out.sqlite.."));
        return rules;
    }

    private boolean crossesIntoAnotherModuleInternalPackage(Dependency dependency) {
        String targetPackage = dependency.getTargetClass().getPackageName();
        if (!targetPackage.startsWith(BASE + ".") || !targetPackage.contains(".internal.")) {
            return false;
        }
        String originModule = moduleOf(dependency.getOriginClass());
        String targetModule = moduleOf(dependency.getTargetClass());
        return originModule != null && targetModule != null && !originModule.equals(targetModule);
    }

    private static String moduleOf(JavaClass type) {
        String packageName = type.getPackageName();
        String prefix = BASE + ".";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String remainder = packageName.substring(prefix.length());
        int separator = remainder.indexOf('.');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }

    private static List<String> findCycle(Map<String, Set<String>> graph) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String node : graph.keySet()) {
            List<String> cycle = visit(node, graph, visited, active, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return List.of();
    }

    private static List<String> visit(
            String node,
            Map<String, Set<String>> graph,
            Set<String> visited,
            Set<String> active,
            Deque<String> path) {
        if (active.contains(node)) {
            List<String> cycle = new ArrayList<>();
            boolean copy = false;
            for (String entry : path) {
                copy |= entry.equals(node);
                if (copy) {
                    cycle.add(entry);
                }
            }
            cycle.add(node);
            return cycle;
        }
        if (!visited.add(node)) {
            return List.of();
        }
        active.add(node);
        path.addLast(node);
        for (String target : graph.getOrDefault(node, Set.of())) {
            List<String> cycle = visit(target, graph, visited, active, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.removeLast();
        active.remove(node);
        return List.of();
    }
}
