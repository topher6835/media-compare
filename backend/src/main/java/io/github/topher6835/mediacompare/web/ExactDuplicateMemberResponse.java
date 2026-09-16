package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.matching.ExactDuplicateMember;

public record ExactDuplicateMemberResponse(long contentRecordId, long sizeBytes) {

    public static ExactDuplicateMemberResponse from(ExactDuplicateMember member) {
        return new ExactDuplicateMemberResponse(member.contentRecordId(), member.sizeBytes());
    }
}
