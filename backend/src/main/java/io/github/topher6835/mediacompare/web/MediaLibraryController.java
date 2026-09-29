package io.github.topher6835.mediacompare.web;

import io.github.topher6835.mediacompare.library.MediaLibraryPage;
import io.github.topher6835.mediacompare.library.MediaLibraryService;
import io.github.topher6835.mediacompare.preview.ThumbnailScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@RequestMapping("/api/media-library")
public class MediaLibraryController {
    private static final Logger log = LoggerFactory.getLogger(MediaLibraryController.class);
    private final MediaLibraryService library;
    private final ThumbnailScheduler scheduler;

    public MediaLibraryController(MediaLibraryService library, ThumbnailScheduler scheduler) {
        this.library = library;
        this.scheduler = scheduler;
    }

    /** Database-only projection: never checks files, renders images, or submits tasks. */
    @GetMapping("/items")
    public ResponseEntity<MediaLibraryPage> items(@RequestParam(required = false) Long afterFileEntryId,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(library.findItems(afterFileEntryId, limit));
    }

    /** Admission only: the worker evaluates then-current source evidence asynchronously. */
    @PostMapping("/thumbnails")
    public ResponseEntity<ThumbnailScheduleResponse> thumbnails(@RequestBody ThumbnailScheduleRequest request) {
        var results = request.fileEntryIds().stream().map(scheduler::schedule).toList();
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(new ThumbnailScheduleResponse(results));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Void> invalid() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Void> failure(RuntimeException failure) {
        log.error("Media library API failed ({})", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().cacheControl(CacheControl.noStore()).build();
    }
}
