package io.github.topher6835.mediacompare.web;

import java.util.List;

import io.github.topher6835.mediacompare.scan.ScanRun;
import io.github.topher6835.mediacompare.scan.ScanRunDetails;

public record ScanRunResponse(
        long id,
        String requestType,
        String status,
        long createdAtMs,
        List<ScanRunSourceResponse> sources) {

    public static ScanRunResponse from(ScanRunDetails details) {
        ScanRun scanRun = details.scanRun();
        return new ScanRunResponse(
                scanRun.id(),
                scanRun.requestType(),
                scanRun.status(),
                scanRun.createdAtMs(),
                details.sources().stream()
                        .map(ScanRunSourceResponse::from)
                        .toList());
    }
}
