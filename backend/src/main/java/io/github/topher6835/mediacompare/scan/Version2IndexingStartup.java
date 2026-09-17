package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.config.CatalogOwnership;
import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobRepository;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class Version2IndexingStartup implements InitializingBean {
    private final JobRepository jobs;
    private final Version2InterruptionRecovery recovery;

    public Version2IndexingStartup(CatalogOwnership ownership, JobRepository jobs,
            Version2InterruptionRecovery recovery) {
        this.jobs = jobs;
        this.recovery = recovery;
    }

    @Override
    public void afterPropertiesSet() {
        for (Job job : jobs.findActiveVersion2ScanJobs()) {
            recovery.failIfActive(job.id(), Version2InterruptionRecovery.RESTART_MESSAGE);
        }
    }
}
