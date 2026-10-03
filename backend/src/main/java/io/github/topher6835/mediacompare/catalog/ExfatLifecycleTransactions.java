package io.github.topher6835.mediacompare.catalog;

import java.util.function.Supplier;
import io.github.topher6835.mediacompare.filesystem.ExfatAuthorityWindowRegistry;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.WindowsDurableEvidenceFormat;
import io.github.topher6835.mediacompare.filesystem.WindowsExfatEvidenceCodec;
import io.github.topher6835.mediacompare.filesystem.WindowsNtfsEvidenceCodec;
import io.github.topher6835.mediacompare.location.LocationDialect;
import io.github.topher6835.mediacompare.location.LocationPathCodec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Outer nontransactional boundary: gate -> writer transaction -> commit -> gate release. */
@Component
public class ExfatLifecycleTransactions {
    private final ExfatAuthorityWindowRegistry registry;
    private final TransactionTemplate transactions;
    private final CatalogRepository sources;
    private final LocationContextRepository contexts;

    public ExfatLifecycleTransactions(ExfatAuthorityWindowRegistry registry, PlatformTransactionManager manager,
            CatalogRepository sources, LocationContextRepository contexts) {
        this.registry = registry;
        this.sources = sources; this.contexts = contexts;
        transactions = new TransactionTemplate(manager);
    }

    public <T> T source(long sourceId, Supplier<T> action) {
        if (registry.ownsSource(sourceId)) {
            return execute(() -> registry.invalidateSource(sourceId), () -> {
                sourceUsesExfat(sourceId); // Revoke even if persisted evidence has become invalid.
                return action.get();
            });
        }
        if (!sourceUsesExfat(sourceId)) return transactions.execute(status -> action.get());
        return execute(() -> registry.invalidateSource(sourceId), action);
    }

    public <T> T context(String contextId, Supplier<T> action) {
        if (registry.ownsContext(contextId)) {
            return execute(() -> registry.invalidateVolume(contextId), () -> {
                contextUsesExfat(contextId);
                return action.get();
            });
        }
        if (!contextUsesExfat(contextId)) return transactions.execute(status -> action.get());
        return execute(() -> registry.invalidateVolume(contextId), action);
    }

    private boolean sourceUsesExfat(long sourceId) {
        var source = sources.findSourceById(sourceId).orElse(null);
        if (source == null || !LocationDialect.WINDOWS_DRIVE.persistedName().equals(source.rootPathDialect())
                || source.boundLocationContextId() == null && source.bindingEvidenceJson() == null) return false;
        var profile = WindowsDurableEvidenceFormat.sourceProfile(source.bindingEvidenceJson());
        if (profile == FileSystemProfile.EXFAT) {
            new WindowsExfatEvidenceCodec().decodeSource(source.bindingEvidenceJson());
            return true;
        }
        new WindowsNtfsEvidenceCodec().decodeSource(source.bindingEvidenceJson());
        return false;
    }

    private boolean contextUsesExfat(String contextId) {
        var context = contexts.findById(contextId).orElse(null);
        if (context == null) return false;
        final LocationDialect dialect;
        try {
            dialect = new LocationPathCodec().decode(context.anchorLocationPath()).dialect();
        } catch (IllegalArgumentException failure) {
            return false; // Let the lifecycle writer report its established invalid-anchor diagnostic.
        }
        if (dialect != LocationDialect.WINDOWS_DRIVE || context.continuityEvidenceJson() == null
                && context.continuityStatus() == LocationContext.ContinuityStatus.REVIEW_REQUIRED) return false;
        var profile = WindowsDurableEvidenceFormat.contextProfile(context.continuityEvidenceJson());
        if (profile == FileSystemProfile.EXFAT) {
            new WindowsExfatEvidenceCodec().decodeContext(context.continuityEvidenceJson());
            return true;
        }
        new WindowsNtfsEvidenceCodec().decodeContext(context.continuityEvidenceJson());
        return false;
    }

    private <T> T execute(Runnable invalidate, Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Lifecycle entry must acquire publication gate before a writer transaction");
        }
        return registry.transition(() -> {
            // Conservative revocation is permitted even when the durable transition is rejected/rolled back.
            invalidate.run();
            return transactions.execute(status -> action.get());
        });
    }
}
