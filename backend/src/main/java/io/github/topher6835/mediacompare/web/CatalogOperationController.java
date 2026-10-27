package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.operation.CatalogOperationFailure;
import io.github.topher6835.mediacompare.operation.CatalogOperationRequest;
import io.github.topher6835.mediacompare.operation.CatalogOperations;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

/** Fast reservation/submission/status calls; filesystem work stays on the catalog worker. */
@RestController
@RequestMapping("/api/catalog-operations")
public class CatalogOperationController {
    private final CatalogOperations operations;
    public CatalogOperationController(CatalogOperations operations) { this.operations = operations; }

    @GetMapping
    public ResponseEntity<java.util.Map<String, String>> runtime() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(java.util.Map.of("runtimeId", operations.runtimeId()));
    }

    @PostMapping
    public ResponseEntity<CatalogOperations.Status> reserve() {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(operations.reserve());
    }

    @PostMapping("/{id}")
    public ResponseEntity<CatalogOperations.Status> submit(@PathVariable String id,
            @RequestBody CatalogOperationRequest request,
            @RequestHeader(value = "X-Media-Compare-Reveal", required = false) String actionHeader) {
        if (request.kind() == CatalogOperationRequest.Kind.REVEAL_FILE && !"1".equals(actionHeader)) {
            throw new CatalogOperationFailure(400, "ACTION_HEADER_REQUIRED");
        }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(operations.submit(id, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CatalogOperations.Status> status(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.status(id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<CatalogOperations.Status> cancel(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operations.cancel(id));
    }

    @ExceptionHandler(CatalogOperationFailure.class)
    public ResponseEntity<Failure> failure(CatalogOperationFailure failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).body(new Failure(failure.code()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Failure> invalid() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(new Failure("INVALID_REQUEST"));
    }

    public record Failure(String code) { }
}
