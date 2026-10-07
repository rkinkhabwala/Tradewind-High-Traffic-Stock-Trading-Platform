package com.tradewind.portfolio;

import com.tradewind.common.arch.ExcludeTestClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.MoneyArchitectureRules;

/** portfolio-service is the source of truth for money: no floating point anywhere in it. */
@AnalyzeClasses(packages = "com.tradewind.portfolio", importOptions = ExcludeTestClasses.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPoint = MoneyArchitectureRules.noFloatingPointIn("com.tradewind.portfolio..");
}
