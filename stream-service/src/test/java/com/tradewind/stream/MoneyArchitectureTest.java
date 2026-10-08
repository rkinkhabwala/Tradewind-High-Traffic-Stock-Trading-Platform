package com.tradewind.stream;

import com.tradewind.common.arch.ExcludeTestClasses;
import com.tradewind.common.arch.MoneyArchitectureRules;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Prices pass through stream-service as decimal strings; nothing here may turn them into floating point. */
@AnalyzeClasses(packages = "com.tradewind.stream", importOptions = ExcludeTestClasses.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPoint = MoneyArchitectureRules.noFloatingPointIn("com.tradewind.stream..");
}
