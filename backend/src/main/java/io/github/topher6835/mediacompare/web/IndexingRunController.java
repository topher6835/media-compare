package io.github.topher6835.mediacompare.web;

import java.net.URI;
import java.util.NoSuchElementException;
import io.github.topher6835.mediacompare.scan.IndexingRunReadService;
import io.github.topher6835.mediacompare.scan.IndexingRunService;
import io.github.topher6835.mediacompare.scan.Version2ExecutionConflictException;
import io.github.topher6835.mediacompare.scan.Version2SchedulingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/indexing-runs")
public class IndexingRunController {
    private static final Logger log = LoggerFactory.getLogger(IndexingRunController.class);
    private final IndexingRunService starts;
    private final IndexingRunReadService reads;

    public IndexingRunController(IndexingRunService starts, IndexingRunReadService reads) {
        this.starts = starts;
        this.reads = reads;
    }

    @PostMapping
    public ResponseEntity<IndexingRunResponse> start(@RequestBody CreateIndexingRunRequest request) {
        var result = starts.start(request.requestKey(), request.sourceIds());
        return ResponseEntity.status(result.created() ? 202 : 200)
                .location(URI.create("/api/indexing-runs/" + result.run().scanRun().id()))
                .cacheControl(CacheControl.noStore()).body(IndexingRunResponse.from(result.run()));
    }

    @GetMapping("/{scanRunId}")
    public ResponseEntity<IndexingRunResponse> detail(@PathVariable long scanRunId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(IndexingRunResponse.from(reads.require(scanRunId)));
    }

    @GetMapping("/source-status")
    public ResponseEntity<IndexingRunReadService.SourceStatus> sourceStatus() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reads.sourceStatus());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Void> invalid() { return error(400); }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> missing() { return error(404); }

    @ExceptionHandler(Version2ExecutionConflictException.class)
    public ResponseEntity<Void> conflict() { return error(409); }

    @ExceptionHandler(Version2SchedulingException.class)
    public ResponseEntity<Void> unavailable() { return error(503); }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> internalFailure(RuntimeException failure) {
        log.error("Indexing API operation failed", failure);
        return error(500);
    }

    private static ResponseEntity<Void> error(int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
