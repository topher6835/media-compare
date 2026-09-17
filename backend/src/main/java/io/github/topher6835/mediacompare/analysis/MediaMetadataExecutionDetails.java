package io.github.topher6835.mediacompare.analysis;

import java.util.List;

import io.github.topher6835.mediacompare.job.Job;
import io.github.topher6835.mediacompare.job.JobStage;

public record MediaMetadataExecutionDetails(Job job, List<JobStage> stages) {
}
