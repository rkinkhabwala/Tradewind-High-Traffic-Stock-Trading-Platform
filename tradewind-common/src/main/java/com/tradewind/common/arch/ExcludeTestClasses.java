package com.tradewind.common.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/**
 * Like ArchUnit's DoNotIncludeTests, but independent of the build directory's name (the build writes to
 * {@code target.nosync/} so iCloud does not sync it). Anything compiled into a {@code test-classes} folder is a test.
 */
public final class ExcludeTestClasses implements ImportOption {

    @Override
    public boolean includes(Location location) {
        return !location.contains("/test-classes/");
    }
}
