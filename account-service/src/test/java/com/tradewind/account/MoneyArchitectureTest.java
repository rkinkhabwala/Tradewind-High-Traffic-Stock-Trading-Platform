package com.tradewind.account;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.MoneyArchitectureRules;

@AnalyzeClasses(packages = "com.tradewind.account", importOptions = ImportOption.DoNotIncludeTests.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPointInFunding = MoneyArchitectureRules.noFloatingPointIn("..account.funding..");
}
