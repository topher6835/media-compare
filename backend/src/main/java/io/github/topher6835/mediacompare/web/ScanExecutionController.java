package io.github.topher6835.mediacompare.web;

import java.net.URI;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.scan.ScanExecutionAlreadyExistsException;
import io.github.topher6835.mediacompare.scan.ScanExecutionDetails;
import io.github.topher6835.mediacompare.scan.ScanExecutionService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scan-runs/{scanRunId}/execution")
public class ScanExecutionController {

    private final ScanExecutionService scanExecutionService;

    public ScanExecutionController(ScanExecutionService scanExecutionService) {
        this.scanExecutionService = scanExecutionService;
    }

    @PostMapping
    public ResponseEntity<ScanExecutionResponse> create(@PathVariable long scanRunId) {
        ScanExecutionDetails details = scanExecutionService.create(scanRunId);
        URI location = URI.create("/api/scan-runs/" + scanRunId + "/execution");
        return ResponseEntity.created(location).body(ScanExecutionResponse.from(details));
    }

    @GetMapping
    public ResponseEntity<ScanExecutionResponse> findByScanRunId(@PathVariable long scanRunId) {
        return scanExecutionService.findByScanRunId(scanRunId)
                .map(ScanExecutionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> missingScanRun() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(ScanExecutionAlreadyExistsException.class)
    public ResponseEntity<Void> executionAlreadyExists() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
