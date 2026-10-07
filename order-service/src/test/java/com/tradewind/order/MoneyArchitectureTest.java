package com.tradewind.order;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.MoneyArchitectureRules;

@AnalyzeClasses(packages = "com.tradewind.order", importOptions = ImportOption.DoNotIncludeTests.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPoint = MoneyArchitectureRules.noFloatingPointIn(
            "com.tradewind.order.order..", "com.tradewind.order.portfolio..");
}
