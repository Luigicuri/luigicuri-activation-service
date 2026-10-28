package com.luigicuri.activation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** R10.7: dependency rules between packages, checked on every build. */
@AnalyzeClasses(packages = "com.luigicuri.activation", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "com.luigicuri.activation";

    /** The domain holds the rules; it knows nothing about HTTP, Spring or the other layers. */
    @ArchTest
    static final ArchRule domainDependsOnlyOnTheJdkAndJpa = classes()
            .that().resideInAPackage(ROOT + ".request.domain..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage(ROOT + ".request.domain..", "java..", "jakarta.persistence..");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().resideInAPackage(ROOT + ".request.api..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".request.persistence..");

    /** The provider client speaks in its own types; it never sees the JPA entity or the feature. */
    @ArchTest
    static final ArchRule providerDoesNotKnowTheFeature = noClasses()
            .that().resideInAPackage(ROOT + ".provider..")
            .should().dependOnClassesThat().resideInAPackage(ROOT + ".request..");

    /** Cross-cutting code is reused by features, never the other way around. */
    @ArchTest
    static final ArchRule sharedDoesNotDependOnFeatures = noClasses()
            .that().resideInAPackage(ROOT + ".shared..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".request..", ROOT + ".provider..");

    @ArchTest
    static final ArchRule onlyTheApiLayerExposesHttpEndpoints = classes()
            .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage(ROOT + ".request.api..");

    @ArchTest
    static final ArchRule noCyclesBetweenTopLevelPackages = slices()
            .matching(ROOT + ".(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule noCyclesBetweenFeatureLayers = slices()
            .matching(ROOT + ".request.(*)..")
            .should().beFreeOfCycles();
}
