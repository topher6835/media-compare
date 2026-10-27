package io.github.topher6835.mediacompare.operation;

import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextStructuralOverlapException;
import io.github.topher6835.mediacompare.catalog.SourceAuthorityProjection;
import io.github.topher6835.mediacompare.catalog.SourceBindingConflictException;
import io.github.topher6835.mediacompare.catalog.SourcePreparationException;
import io.github.topher6835.mediacompare.catalog.SourcePreparationService;
import io.github.topher6835.mediacompare.matching.CleanupPreflightService;
import io.github.topher6835.mediacompare.matching.RevealFileService;
import io.github.topher6835.mediacompare.web.SourceResponse;
import org.springframework.stereotype.Component;

/** Runs the existing safety services unchanged, outside HTTP threads and ambient transactions. */
@Component
public final class CatalogOperationActions {
    private final CleanupPreflightService preflight;
    private final SourcePreparationService preparation;
    private final SourceAuthorityProjection authority;
    private final RevealFileService reveal;

    public CatalogOperationActions(CleanupPreflightService preflight, SourcePreparationService preparation,
            SourceAuthorityProjection authority, RevealFileService reveal) {
        this.preflight = preflight;
        this.preparation = preparation;
        this.authority = authority;
        this.reveal = reveal;
    }

    public Object execute(CatalogOperationRequest request) {
        try {
            return switch (request.kind()) {
                case CLEANUP_PREFLIGHT -> preflight.preflight(request.digestHex(), request.preflight());
                case PREPARE_SOURCE -> {
                    var source = preparation.prepare(request.sourceId());
                    yield SourceResponse.from(source, authority.project(source));
                }
                case REVEAL_FILE -> reveal(request.fileEntryId());
            };
        } catch (SourcePreparationException failure) {
            int status = switch (failure.code()) {
                case PATH_UNAVAILABLE, PROFILE_UNSUPPORTED, EVIDENCE_UNCERTAIN -> 422;
                case PROBE_ERROR -> 503;
                case STATE_CHANGED -> 409;
            };
            throw new CatalogOperationFailure(status, failure.code().name());
        } catch (SourceBindingConflictException | LocationContextAcceptanceConflictException
                | LocationContextStructuralOverlapException conflict) {
            throw new CatalogOperationFailure(409, "STATE_CHANGED");
        } catch (NoSuchElementException missing) {
            throw new CatalogOperationFailure(404, "NOT_FOUND");
        } catch (IllegalArgumentException invalid) {
            throw new CatalogOperationFailure(400, "INVALID_REQUEST");
        }
    }

    private Object reveal(long fileEntryId) {
        // The same service and status meanings as the synchronous Reveal controller.
        return switch (reveal.reveal(fileEntryId)) {
            case SUCCESS -> null;
            case NOT_FOUND -> throw new CatalogOperationFailure(404, "FILE_ENTRY_NOT_FOUND");
            case STALE_AUTHORITY -> throw new CatalogOperationFailure(409, "AUTHORITY_UNAVAILABLE");
            case FILE_CHANGED -> throw new CatalogOperationFailure(410, "FILE_CHANGED");
            case UNSUPPORTED_HOST -> throw new CatalogOperationFailure(501, "UNSUPPORTED_HOST");
            case FINDER_FAILED -> throw new CatalogOperationFailure(502, "FINDER_FAILED");
        };
    }
}
