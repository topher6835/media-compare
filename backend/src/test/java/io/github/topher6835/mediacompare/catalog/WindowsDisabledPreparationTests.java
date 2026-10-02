package io.github.topher6835.mediacompare.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import io.github.topher6835.mediacompare.filesystem.FileSystemProfile;
import io.github.topher6835.mediacompare.filesystem.HostFileSystem;
import io.github.topher6835.mediacompare.filesystem.HostFileSystems;
import io.github.topher6835.mediacompare.location.LocationPath;
import org.junit.jupiter.api.Test;

class WindowsDisabledPreparationTests {
    @Test
    void detectedExfatCannotReachPreparationBindingOrReady() {
        var sources = mock(CatalogRepository.class);
        when(sources.findSourceById(1)).thenReturn(Optional.of(new Source(1L, "Photos", "C:\\Photos", "C:\\Photos", 0, 1, 1)));
        var contexts = mock(LocationContextRepository.class);
        var activation = mock(LocationContextActivationService.class);
        var acceptance = mock(LocationContextAcceptanceService.class);
        var binding = mock(SourceBindingService.class);
        var probe = mock(SourcePreparationProbe.class);
        var ntfs = mock(WindowsNtfsSourcePreparationService.class);
        var preparation = new SourcePreparationService(sources, contexts, activation, acceptance, binding, probe, ntfs);
        HostFileSystem exfat = new HostFileSystem() {
            @Override public String pathText(LocationPath location) { return "C:/Photos"; }
            @Override public boolean unsafeElement(Path path, BasicFileAttributes attributes) { return true; }
            @Override public FileSystemProfile profile(Path path) { return FileSystemProfile.EXFAT; }
        };
        try (var hosts = mockStatic(HostFileSystems.class)) {
            hosts.when(HostFileSystems::current).thenReturn(exfat);
            hosts.when(HostFileSystems::isWindows).thenReturn(true);
            assertEquals(SourcePreparationException.Code.PROFILE_UNSUPPORTED,
                    assertThrows(SourcePreparationException.class, () -> preparation.prepare(1)).code());
            verifyNoInteractions(contexts, activation, acceptance, binding, probe, ntfs);
        }
    }
}
