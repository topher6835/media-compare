package io.github.topher6835.mediacompare.matching;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.process.BoundedProcessInterruptedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class RevealFileService {
    private final RevealCatalog catalog;
    private final CleanupPreflightFileValidator validator;
    private final FinderRevealProcess finder;
    private final BooleanSupplier macHost;

    @Autowired
    public RevealFileService(RevealCatalog catalog, CleanupPreflightFileValidator validator,
            FinderRevealProcess finder) {
        this(catalog, validator, finder, HostFileSystems::isMacOs);
    }

    public RevealFileService(RevealCatalog catalog, CleanupPreflightFileValidator validator,
            FinderRevealProcess finder, BooleanSupplier macHost) {
        this.catalog = catalog;
        this.validator = validator;
        this.finder = finder;
        this.macHost = macHost;
    }

    public Result reveal(long fileEntryId) {
        if (fileEntryId <= 0) return Result.NOT_FOUND;
        if (catalog.isExfat(fileEntryId)) return Result.STALE_AUTHORITY;
        if (!macHost.getAsBoolean()) return Result.UNSUPPORTED_HOST;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Finder reveal cannot run inside a database transaction");
        }
        var before = catalog.capture(fileEntryId);
        if (before == null) return Result.NOT_FOUND;
        if (before.entry().currentContentId() == null || before.routes().isEmpty()) return Result.STALE_AUTHORITY;
        var checked = validator.validateForReveal(before);
        if (checked.reason() != null) {
            return switch (checked.reason()) {
                case FILESYSTEM_CHANGED, IO_UNAVAILABLE -> Result.FILE_CHANGED;
                default -> Result.STALE_AUTHORITY;
            };
        }
        if (!Objects.equals(before, catalog.capture(fileEntryId))) return Result.STALE_AUTHORITY;
        try {
            return finder.reveal(checked.path()) ? Result.SUCCESS : Result.FINDER_FAILED;
        } catch (BoundedProcessInterruptedException exception) {
            return Result.FINDER_FAILED;
        } catch (RuntimeException exception) {
            return Result.FINDER_FAILED;
        }
    }

    public enum Result {
        SUCCESS, NOT_FOUND, STALE_AUTHORITY, FILE_CHANGED, UNSUPPORTED_HOST, FINDER_FAILED
    }
}
