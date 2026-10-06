package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.catalog.*;
import io.github.topher6835.mediacompare.filesystem.*;
import io.github.topher6835.mediacompare.job.JobRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

/** Enables only explicitly constructed test services, never production policy or public endpoints. */
@TestConfiguration(proxyBeanMethods = false)
public class MountedSlice5ScanConfiguration {
    @Bean @Primary ExfatScanBundles mountedScans(CatalogRepository catalog, LocationContextRepository contexts,
            SourceBindingPeriodRepository periods, ScanRepository scans, JobRepository jobs,
            ExfatAuthorityWindowRegistry registry, PlatformTransactionManager manager) {
        return new ExfatScanBundles(catalog, contexts, periods, scans, jobs, registry, manager, true);
    }
    @Bean @Primary WindowsExfatDiscoveryWalker mountedWalker(ExfatScanBundles bundles,
            ExfatObservationPublicationService publication, ExfatAuthorityWindowRegistry registry, MountedSlice5Fixture fixture) {
        return new WindowsExfatDiscoveryWalker(bundles, publication, registry,
                new WindowsExfatNativeDiscoveryAccess(fixture.host));
    }
}
