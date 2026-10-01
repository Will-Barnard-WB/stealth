package dev.stealth.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The bearer token clients must send, in {@code ~/.stealth/mcp-token}. A localhost port is
 * reachable by every process on the machine, so the token is what limits the server to clients the
 * user registered with {@code stealth mcp install}.
 */
public final class McpToken {

    public static final String FILE_NAME = "mcp-token";

    private McpToken() {}

    /** {@code ~/.stealth}. */
    public static Path defaultHome() {
        return Path.of(System.getProperty("user.home"), ".stealth");
    }

    /** The token in {@code home}, created (readable by the owner only) if there isn't one. */
    public static String loadOrCreate(Path home) throws IOException {
        Path file = home.resolve(FILE_NAME);
        if (Files.isRegularFile(file)) {
            String token = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return create(home);
    }

    /** Replaces the token; clients registered with the old one stop working. */
    public static String create(Path home) throws IOException {
        Files.createDirectories(home);
        Path file = home.resolve(FILE_NAME);
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Path temp = home.resolve(FILE_NAME + ".tmp");
        Files.deleteIfExists(temp);
        if (home.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.createFile(
                        temp,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (FileAlreadyExistsException e) {
                // Lost a race with another install; overwrite below
            }
        }
        // On Windows the user profile directory is private to the user already
        Files.writeString(temp, token, StandardCharsets.UTF_8);
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return token;
    }
}
