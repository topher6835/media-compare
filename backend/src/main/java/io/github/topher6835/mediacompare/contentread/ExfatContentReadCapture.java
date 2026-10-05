package io.github.topher6835.mediacompare.contentread;

import io.github.topher6835.mediacompare.analysis.AnalysisRecord;
import io.github.topher6835.mediacompare.analysis.ContentHash;
import io.github.topher6835.mediacompare.catalog.ContentRecord;
import io.github.topher6835.mediacompare.catalog.FileEntry;
import io.github.topher6835.mediacompare.catalog.SourceMembership;

/** Immutable queued evidence; deliberately contains no handle or operation lease. */
public record ExfatContentReadCapture(ExfatContentReadAuthority authority, FileEntry file,
        SourceMembership membership, ContentRecord content, AnalysisRecord shaAnalysis, ContentHash sha) { }
