package io.cilab;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionTest {

    @Test
    void missingVersionFileFallsBackToUnknown() {
        assertEquals("unknown", App.versionFrom(Path.of("/definitely/not/here")));
    }
}
