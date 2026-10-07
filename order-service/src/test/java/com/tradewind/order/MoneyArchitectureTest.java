package com.tradewind.order;

import com.tradewind.common.arch.ExcludeTestClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.MoneyArchitectureRules;

@AnalyzeClasses(packages = "com.tradewind.order", importOptions = ExcludeTestClasses.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPoint = MoneyArchitectureRules.noFloatingPointIn(
            "com.tradewind.order.order..", "com.tradewind.order.portfolio..");
}
