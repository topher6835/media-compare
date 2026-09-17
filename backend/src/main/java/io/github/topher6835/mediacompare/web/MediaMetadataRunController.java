package io.github.topher6835.mediacompare.web;

import java.net.URI;
import java.util.NoSuchElementException;

import io.github.topher6835.mediacompare.analysis.ImageMetadataStageResultCodec;
import io.github.topher6835.mediacompare.analysis.MediaMetadataBackgroundService;
import io.github.topher6835.mediacompare.analysis.MediaMetadataJobConflictException;
import io.github.topher6835.mediacompare.analysis.MediaMetadataJobService;
import io.github.topher6835.mediacompare.analysis.MediaMetadataSchedulingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/media-metadata-runs")
public class MediaMetadataRunController {
    private static final Logger log = LoggerFactory.getLogger(MediaMetadataRunController.class);

    private final MediaMetadataBackgroundService background;
    private final MediaMetadataJobService jobs;
    private final ImageMetadataStageResultCodec resultCodec;

    public MediaMetadataRunController(MediaMetadataBackgroundService background,
            MediaMetadataJobService jobs, ImageMetadataStageResultCodec resultCodec) {
        this.background = background;
        this.jobs = jobs;
        this.resultCodec = resultCodec;
    }

    @PostMapping
    public ResponseEntity<MediaMetadataRunResponse> start() {
        var details = background.start();
        return ResponseEntity.status(202)
                .location(URI.create("/api/media-metadata-runs/" + details.job().id()))
                .cacheControl(CacheControl.noStore())
                .body(MediaMetadataRunResponse.from(details, resultCodec));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<MediaMetadataRunResponse> detail(@PathVariable long jobId) {
        if (jobId <= 0) {
            throw new NoSuchElementException("Metadata Job does not exist");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(MediaMetadataRunResponse.from(jobs.find(jobId), resultCodec));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Void> invalid() { return error(400); }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> missing() { return error(404); }

    @ExceptionHandler(MediaMetadataJobConflictException.class)
    public ResponseEntity<Void> conflict() { return error(409); }

    @ExceptionHandler(MediaMetadataSchedulingException.class)
    public ResponseEntity<Void> unavailable() { return error(503); }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> internalFailure(RuntimeException failure) {
        log.error("Media metadata API operation failed", failure);
        return error(500);
    }

    private static ResponseEntity<Void> error(int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
