package io.github.topher6835.mediacompare.scan;

import java.util.List;

public record ScanRunDetails(ScanRun scanRun, List<ScanRunSource> sources) {
}
