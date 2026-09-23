package com.janspace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.base.DescribedPredicate;
import java.util.List;
import java.util.stream.Stream;

/**
 * package-architecture.md §3·§8 의 의존 방향을 고정한다. 기능 패키지가 아직 없어도 통과하도록 allowEmptyShould 를 켠다.
 */
@AnalyzeClasses(packages = "com.janspace", importOptions = DoNotIncludeTests.class)
class ArchitectureTest {

    static final List<String> FEATURES = List.of(
            "account", "event", "catalog", "setlist", "attendance", "photocard", "notification", "ops");

    static String[] featurePackages() {
        return FEATURES.stream().map(f -> "com.janspace." + f + "..").toArray(String[]::new);
    }

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..application..", "..infrastructure..", "com.janspace.integration..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule application_does_not_depend_on_api_or_adapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..infrastructure..", "com.janspace.integration..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule api_does_not_depend_on_infrastructure = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule api_does_not_depend_on_domain_repositories = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat(new DescribedPredicate<>("domain repositories") {
                @Override
                public boolean test(JavaClass clazz) {
                    return clazz.getPackageName().contains(".domain") && clazz.getSimpleName().endsWith("Repository");
                }
            })
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule common_is_independent = noClasses()
            .that().resideInAPackage("com.janspace.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    Stream.concat(Stream.of(featurePackages()),
                            Stream.of("com.janspace.integration..", "com.janspace.bootstrap.."))
                            .toArray(String[]::new))
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule integration_does_not_depend_on_features = noClasses()
            .that().resideInAPackage("com.janspace.integration..")
            .should().dependOnClassesThat().resideInAnyPackage(featurePackages())
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule feature_slices_are_free_of_cycles = slices()
            .matching("com.janspace.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    // 기능 F 의 api/domain/infrastructure 는 F 자신과 bootstrap 만 참조할 수 있다
    @ArchTest
    static void feature_internals_are_not_referenced_from_outside(JavaClasses classes) {
        for (String feature : FEATURES) {
            String root = "com.janspace." + feature;
            noClasses()
                    .that().resideOutsideOfPackages(root + "..", "com.janspace.bootstrap..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            root + ".api..", root + ".domain..", root + ".infrastructure..")
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }
}
