package cn.tohsaka.factory.zstdnet.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PremiumPlayerRegistryTest {

    @TempDir
    Path tempDir;

    private Path registry() {
        return tempDir.resolve(PremiumPlayerRegistry.FILE_NAME);
    }

    @Test
    void recordsAndReadsBackCaseInsensitively() {
        UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        PremiumPlayerRegistry.recordVerified(registry(), "Notch", id);
        assertEquals(id, PremiumPlayerRegistry.recordedUuid(registry(), "Notch"));
        assertEquals(id, PremiumPlayerRegistry.recordedUuid(registry(), "notch"));
        assertEquals(id, PremiumPlayerRegistry.recordedUuid(registry(), "NOTCH"));
    }

    @Test
    void unknownNameReturnsNull() {
        assertNull(PremiumPlayerRegistry.recordedUuid(registry(), "Nobody"));
        assertNull(PremiumPlayerRegistry.recordedUuid(registry(), null));
        assertNull(PremiumPlayerRegistry.recordedUuid(registry(), "  "));
    }

    @Test
    void reRecordUpdatesUuid() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PremiumPlayerRegistry.recordVerified(registry(), "Steve", first);
        PremiumPlayerRegistry.recordVerified(registry(), "Steve", second);
        assertEquals(second, PremiumPlayerRegistry.recordedUuid(registry(), "Steve"));
    }

    @Test
    void ignoresBlankNamesAndNullIds() {
        PremiumPlayerRegistry.recordVerified(registry(), "", UUID.randomUUID());
        PremiumPlayerRegistry.recordVerified(registry(), null, UUID.randomUUID());
        PremiumPlayerRegistry.recordVerified(registry(), "Alex", null);
        assertNull(PremiumPlayerRegistry.recordedUuid(registry(), "Alex"));
    }
}
