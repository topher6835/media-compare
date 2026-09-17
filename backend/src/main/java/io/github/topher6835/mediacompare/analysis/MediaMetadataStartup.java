package io.github.topher6835.mediacompare.analysis;

import io.github.topher6835.mediacompare.config.CatalogOwnership;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;

@Component
@DependsOnDatabaseInitialization
public class MediaMetadataStartup implements InitializingBean {

    private final MediaMetadataInterruptionRecovery recovery;

    public MediaMetadataStartup(
            CatalogOwnership ownership, MediaMetadataInterruptionRecovery recovery) {
        this.recovery = recovery;
    }

    @Override
    public void afterPropertiesSet() {
        recovery.recoverAtStartup();
    }
}
