package io.github.topher6835.mediacompare.web;

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

    public SourceController(SourceService sourceService, SourcePreparationService preparation) {
        this.sourceService = sourceService;
        this.preparation = preparation;
    }

    @PostMapping
    public ResponseEntity<SourceResponse> register(@RequestBody RegisterSourceRequest request) {
        Source source = sourceService.register(request.name(), request.rootPath());
        URI location = URI.create("/api/sources/" + source.id());
        return ResponseEntity.created(location).body(SourceResponse.from(source));
    }

    @GetMapping
    public List<SourceResponse> findAll() {
        return sourceService.findAll().stream()
                .map(SourceResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SourceResponse> findById(@PathVariable long id) {
        return sourceService.findById(id)
                .map(SourceResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/prepare")
    public ResponseEntity<SourceResponse> prepare(@PathVariable long id) {
        return ResponseEntity.ok(SourceResponse.from(preparation.prepare(id)));
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> invalidRegistration() {
        return ResponseEntity.badRequest().build();
    }

    public record PreparationError(SourcePreparationException.Code code) {
    }
}
