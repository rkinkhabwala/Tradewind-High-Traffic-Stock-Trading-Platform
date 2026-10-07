package com.tradewind.common.arch;

import com.tradewind.common.arch.ExcludeTestClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Fails the build if floating point appears in tradewind-common's money-carrying packages. */
@AnalyzeClasses(packages = "com.tradewind.common", importOptions = ExcludeTestClasses.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPointInMoneyPackages =
            MoneyArchitectureRules.noFloatingPointIn("..money..", "..event..", "..idempotency..", "..quote..", "..order..", "..outbox..", "..execution..", "..portfolio..");
}
