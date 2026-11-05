package com.stagelog;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.List;
import java.util.stream.Stream;

/**
 * 패키지 컨벤션의 일곱 규칙으로 계층·기능 경계를 고정한다. 규칙을 바꾸면 컨벤션 문서의 규칙 표도 함께 고친다.
 * 규칙이 고르는 클래스가 하나도 없으면 실패한다(allowEmptyShould 기본값). 패키지 이름이 바뀌어 규칙이 조용히 통과하는 것을 막는다.
 */
@AnalyzeClasses(packages = "com.stagelog", importOptions = DoNotIncludeTests.class)
class ArchitectureTest {

    static final List<String> FEATURES = List.of(
            "account", "event", "catalog", "setlist", "attendance", "photocard", "notification", "ops", "ticket", "media");

    static String[] featurePackages() {
        return FEATURES.stream().map(f -> "com.stagelog." + f + "..").toArray(String[]::new);
    }

    // 규칙 1
    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..application..", "..infrastructure..", "com.stagelog.integration..");

    // 규칙 2
    @ArchTest
    static final ArchRule application_does_not_depend_on_api_or_adapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..api..", "..infrastructure..", "com.stagelog.integration..");

    // 규칙 3 (앞부분)
    @ArchTest
    static final ArchRule api_does_not_depend_on_infrastructure = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    // 규칙 3 (뒷부분)
    @ArchTest
    static final ArchRule api_does_not_depend_on_domain_repositories = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat(new DescribedPredicate<>("domain repositories") {
                @Override
                public boolean test(JavaClass clazz) {
                    return clazz.getPackageName().contains(".domain") && clazz.getSimpleName().endsWith("Repository");
                }
            });

    // 규칙 4
    @ArchTest
    static final ArchRule common_is_independent = noClasses()
            .that().resideInAPackage("com.stagelog.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    Stream.concat(Stream.of(featurePackages()),
                            Stream.of("com.stagelog.integration..", "com.stagelog.bootstrap.."))
                            .toArray(String[]::new));

    // 규칙 5. 기능 F 의 api/domain/infrastructure 는 F 자신과 bootstrap 만 참조할 수 있다. 기능별 위반을 한 번에 모두 보고한다
    @ArchTest
    static final ArchRule feature_internals_are_not_referenced_from_outside = CompositeArchRule.of(
            FEATURES.stream().map(ArchitectureTest::internalsOnlyUsedWithin).toList());

    // 규칙 6
    @ArchTest
    static final ArchRule integration_does_not_depend_on_features = noClasses()
            .that().resideInAPackage("com.stagelog.integration..")
            .should().dependOnClassesThat().resideInAnyPackage(featurePackages());

    // 규칙 7
    @ArchTest
    static final ArchRule feature_slices_are_free_of_cycles = slices()
            .matching("com.stagelog.(*)..")
            .should().beFreeOfCycles();

    static ArchRule internalsOnlyUsedWithin(String feature) {
        String root = "com.stagelog." + feature;
        return classes()
                .that().resideInAnyPackage(root + ".api..", root + ".domain..", root + ".infrastructure..")
                .should().onlyHaveDependentClassesThat().resideInAnyPackage(root + "..", "com.stagelog.bootstrap..");
    }
}
