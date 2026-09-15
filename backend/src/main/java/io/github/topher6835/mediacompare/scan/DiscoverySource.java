package io.github.topher6835.mediacompare.scan;

import io.github.topher6835.mediacompare.catalog.Source;

record DiscoverySource(Source source, ScanRunSource scanRunSource, long traversalGeneration) {
}
