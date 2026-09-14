package io.github.topher6835.mediacompare.scan;

import java.util.List;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobStage;

public record ScanExecutionDetails(Job job, List<JobStage> stages) {
}
