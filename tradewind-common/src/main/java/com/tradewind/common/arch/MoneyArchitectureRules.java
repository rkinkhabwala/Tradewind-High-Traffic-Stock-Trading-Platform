package com.tradewind.common.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * "Money is never double" (spec sections 4 and 10). Each service runs {@link #noFloatingPointIn(String...)}
 * over its money-related packages in an ArchUnit test, so a {@code double} or {@code float} fails the build.
 */
public final class MoneyArchitectureRules {

    private static final String REASON = "money must never be floating point (spec section 4)";
    private static final Set<String> FLOATING_TYPES =
            Set.of("double", "float", Double.class.getName(), Float.class.getName());

    private MoneyArchitectureRules() {
    }

    public static ArchRule noFloatingPointIn(String... packages) {
        return CompositeArchRule.of(noFloatingPointFields(packages))
                .and(noFloatingPointSignatures(packages))
                .and(noFloatingPointConversions(packages));
    }

    public static ArchRule noFloatingPointFields(String... packages) {
        return noFields().that().areDeclaredInClassesThat().resideInAnyPackage(packages)
                .should().haveRawType(double.class)
                .orShould().haveRawType(float.class)
                .orShould().haveRawType(Double.class)
                .orShould().haveRawType(Float.class)
                .because(REASON)
                .allowEmptyShould(true);
    }

    public static ArchRule noFloatingPointSignatures(String... packages) {
        return codeUnits().that().areDeclaredInClassesThat().resideInAnyPackage(packages)
                .should(notUseFloatingPointInSignature())
                .because(REASON)
                .allowEmptyShould(true);
    }

    public static ArchRule noFloatingPointConversions(String... packages) {
        return noClasses().that().resideInAnyPackage(packages)
                .should().callMethod(BigDecimal.class, "doubleValue")
                .orShould().callMethod(BigDecimal.class, "floatValue")
                .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
                .orShould().callConstructor(BigDecimal.class, double.class)
                .orShould().callConstructor(BigDecimal.class, double.class, MathContext.class)
                .because(REASON)
                .allowEmptyShould(true);
    }

    private static ArchCondition<JavaCodeUnit> notUseFloatingPointInSignature() {
        return new ArchCondition<>("not take or return double/float") {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                Stream.concat(codeUnit.getRawParameterTypes().stream(), Stream.of(codeUnit.getRawReturnType()))
                        .map(JavaClass::getName)
                        .filter(FLOATING_TYPES::contains)
                        .findFirst()
                        .ifPresent(type -> events.add(SimpleConditionEvent.violated(
                                codeUnit, codeUnit.getFullName() + " uses " + type)));
            }
        };
    }
}
