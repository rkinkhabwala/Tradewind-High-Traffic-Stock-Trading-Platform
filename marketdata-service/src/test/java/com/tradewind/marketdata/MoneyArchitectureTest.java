package com.tradewind.marketdata;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tradewind.common.arch.MoneyArchitectureRules;

/**
 * Prices served or stored by marketdata-service are never floating point. The {@code simulator} package is
 * deliberately excluded: it runs the random walk in doubles and rounds to whole cents before anything leaves it.
 */
@AnalyzeClasses(packages = "com.tradewind.marketdata", importOptions = ImportOption.DoNotIncludeTests.class)
class MoneyArchitectureTest {

    @ArchTest
    static final ArchRule noFloatingPointOutsideTheSimulator = MoneyArchitectureRules.noFloatingPointIn(
            "..marketdata.quote..", "..marketdata.candle..", "..marketdata.symbol..", "..marketdata.web..");
}
