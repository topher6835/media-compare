package io.github.topher6835.mediacompare.web;

import java.net.URI;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.scan.ScanRunDetails;
import io.github.topher6835.mediacompare.scan.ScanRunService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scan-runs")
public class ScanRunController {

    private final ScanRunService scanRunService;

    public ScanRunController(ScanRunService scanRunService) {
        this.scanRunService = scanRunService;
    }

    @PostMapping
    public ResponseEntity<ScanRunResponse> create(@RequestBody CreateScanRunRequest request) {
        ScanRunDetails details = scanRunService.create(request.sourceIds());
        URI location = URI.create("/api/scan-runs/" + details.scanRun().id());
        return ResponseEntity.created(location).body(ScanRunResponse.from(details));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScanRunResponse> findById(@PathVariable long id) {
        return scanRunService.findById(id)
                .map(ScanRunResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> invalidRequest() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> missingSource() {
        return ResponseEntity.notFound().build();
    }
}
