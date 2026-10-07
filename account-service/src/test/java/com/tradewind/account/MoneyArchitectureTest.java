package com.tradewind.account;

import com.tradewind.common.arch.ExcludeTestClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.MoneyArchitectureRules;

@AnalyzeClasses(packages = "com.tradewind.account", importOptions = ExcludeTestClasses.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPointInFunding = MoneyArchitectureRules.noFloatingPointIn("..account.funding..");
}
