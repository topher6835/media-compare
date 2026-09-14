package io.github.topher6835.mediacompare.web;

import java.util.List;

public record CreateScanRunRequest(List<Long> sourceIds) {
}
