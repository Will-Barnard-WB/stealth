package dev.stealth.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTokenTest {

    @TempDir private Path home;

    @Test
    void loadOrCreate_firstRun_createsARandomTokenAndReusesIt() throws Exception {
        String first = McpToken.loadOrCreate(home);
        String again = McpToken.loadOrCreate(home);

        assertThat(first).hasSizeGreaterThanOrEqualTo(40).matches("[A-Za-z0-9_-]+");
        assertThat(again).isEqualTo(first);
        assertThat(McpToken.loadOrCreate(home.resolve("other"))).isNotEqualTo(first);
    }

    @Test
    void create_existingToken_replacesIt() throws Exception {
        String old = McpToken.loadOrCreate(home);

        String rotated = McpToken.create(home);

        assertThat(rotated).isNotEqualTo(old);
        assertThat(McpToken.loadOrCreate(home)).isEqualTo(rotated);
    }

    @Test
    void create_onPosix_isReadableByTheOwnerOnly() throws Exception {
        assumeThat(home.getFileSystem().supportedFileAttributeViews()).contains("posix");

        McpToken.create(home);

        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(home.resolve(McpToken.FILE_NAME))))
                .isEqualTo("rw-------");
    }
}
