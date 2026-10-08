package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.catalog.SourceAuthorityProjection;
import org.springframework.http.CacheControl;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.catalog.LocationContextAcceptanceConflictException;
import io.github.topher6835.mediacompare.catalog.LocationContextStructuralOverlapException;
import io.github.topher6835.mediacompare.catalog.Source;
import io.github.topher6835.mediacompare.catalog.SourceBindingConflictException;
import io.github.topher6835.mediacompare.catalog.SourcePreparationException;
import io.github.topher6835.mediacompare.catalog.SourcePreparationService;
import io.github.topher6835.mediacompare.catalog.SourceService;
import io.github.topher6835.mediacompare.session.SourceWorkspaceOverlapException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sources")
public class SourceController {

    private final SourceService sourceService;
    private final SourcePreparationService preparation;
    private final SourceAuthorityProjection authority;

    public SourceController(SourceService sourceService, SourcePreparationService preparation,
            SourceAuthorityProjection authority) {
        this.sourceService = sourceService;
        this.preparation = preparation;
        this.authority = authority;
    }

    @PostMapping
    public ResponseEntity<SourceResponse> register(@RequestBody RegisterSourceRequest request) {
        Source source = sourceService.register(request.name(), request.rootPath());
        URI location = URI.create("/api/sources/" + source.id());
        return ResponseEntity.created(location).cacheControl(CacheControl.noStore()).body(response(source));
    }

    @GetMapping
    public ResponseEntity<List<SourceResponse>> findAll() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(sourceService.findAll().stream()
                .map(this::response)
                .toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<SourceResponse> findById(@PathVariable long id) {
        return sourceService.findById(id)
                .map(this::response)
                .map(body -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/prepare")
    public ResponseEntity<SourceResponse> prepare(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(preparation.prepare(id)));
    }

    private SourceResponse response(Source source) { return SourceResponse.from(source, authority.project(source)); }

    @PostMapping("/{id}/release-authority")
    public ResponseEntity<ReleaseSourceAuthorityResponse> release(@PathVariable long id,
            @RequestBody ReleaseSourceAuthorityRequest request) {
        var result = authority.release(id, request.windowId());
        var source = sourceService.findById(id).orElseThrow();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ReleaseSourceAuthorityResponse(response(source), result.releaseState(), result.otherWindowsOnVolumeRemain()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> missingSource() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(SourcePreparationException.class)
    public ResponseEntity<PreparationError> preparationFailure(SourcePreparationException failure) {
        int status = switch (failure.code()) {
            case PATH_UNAVAILABLE, PROFILE_UNSUPPORTED, EVIDENCE_UNCERTAIN -> 422;
            case PROBE_ERROR -> 503;
            case STATE_CHANGED -> 409;
        };
        return ResponseEntity.status(status).body(new PreparationError(failure.code()));
    }

    @ExceptionHandler({SourceBindingConflictException.class,
            LocationContextAcceptanceConflictException.class,
            LocationContextStructuralOverlapException.class})
    public ResponseEntity<PreparationError> preparationConflict() {
        return ResponseEntity.status(409)
                .body(new PreparationError(SourcePreparationException.Code.STATE_CHANGED));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Void> invalidRegistration() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(SourceWorkspaceOverlapException.class)
    public ResponseEntity<SourceRegistrationError> overlappingSource() {
        return ResponseEntity.badRequest().body(new SourceRegistrationError("SOURCE_OVERLAPS_SESSION"));
    }

    public record SourceRegistrationError(String code) {
    }

    public record PreparationError(SourcePreparationException.Code code) {
    }
}
