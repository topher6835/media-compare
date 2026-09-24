package io.github.topher6835.mediacompare.scan.authority;

/** Eligibility of a captured scan observation; only TRUSTED carries a value. */
public enum ScanAuthorityOutcome {
    TRUSTED, UNBOUND, UNSUPPORTED, UNAVAILABLE, UNCERTAIN, STALE, INCOMPLETE
}
