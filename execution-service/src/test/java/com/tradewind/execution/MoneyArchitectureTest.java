package com.tradewind.execution;

import com.tradewind.common.arch.ExcludeTestClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.MoneyArchitectureRules;

/** Fill prices and quantities are never floating point anywhere in execution-service. */
@AnalyzeClasses(packages = "com.tradewind.execution", importOptions = ExcludeTestClasses.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPoint = MoneyArchitectureRules.noFloatingPointIn("com.tradewind.execution..");
}
