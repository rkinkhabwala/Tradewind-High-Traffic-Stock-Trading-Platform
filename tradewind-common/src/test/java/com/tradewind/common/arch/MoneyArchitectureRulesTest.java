package com.tradewind.common.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.fixture.money.CleanMoney;
import com.tradewind.common.arch.fixture.money.FloatConversion;
import com.tradewind.common.arch.fixture.money.FloatField;
import com.tradewind.common.arch.fixture.money.FloatSignature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proves the rule actually catches each way floating point can sneak into money code. */
class MoneyArchitectureRulesTest {

    private static final String MONEY = "..money..";

    @Test
    void catchesFloatingPointField() {
        assertViolates(MoneyArchitectureRules.noFloatingPointFields(MONEY), FloatField.class);
        assertViolates(MoneyArchitectureRules.noFloatingPointIn(MONEY), FloatField.class);
    }

    @Test
    void catchesFloatingPointSignature() {
        assertViolates(MoneyArchitectureRules.noFloatingPointSignatures(MONEY), FloatSignature.class);
        assertViolates(MoneyArchitectureRules.noFloatingPointIn(MONEY), FloatSignature.class);
    }

    @Test
    void catchesDoubleToBigDecimalConversion() {
        assertViolates(MoneyArchitectureRules.noFloatingPointConversions(MONEY), FloatConversion.class);
        assertViolates(MoneyArchitectureRules.noFloatingPointIn(MONEY), FloatConversion.class);
    }

    @Test
    void passesCleanMoneyCode() {
        assertThatCode(() -> MoneyArchitectureRules.noFloatingPointIn(MONEY).check(importClasses(CleanMoney.class)))
                .doesNotThrowAnyException();
    }

    private static void assertViolates(ArchRule rule, Class<?> fixture) {
        assertThatThrownBy(() -> rule.check(importClasses(fixture))).isInstanceOf(AssertionError.class);
    }

    private static JavaClasses importClasses(Class<?>... classes) {
        return new ClassFileImporter().importClasses(classes);
    }
}
