package dev.arctic.local.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Uses the real Windows Credential Manager of the current user, with a throwaway target. */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialStoreTest {

    @Test
    void writesReadsOverwritesAndDeletes() {
        var store = new WindowsCredentialStore();
        String target = "Arctic/test-" + UUID.randomUUID();
        try {
            assertThat(store.read(target)).isEmpty();

            store.write(target, "s3cret-ä-€-😀");
            assertThat(store.read(target)).contains("s3cret-ä-€-😀");

            store.write(target, "second");
            assertThat(store.read(target)).contains("second");

            store.delete(target);
            assertThat(store.read(target)).isEmpty();
            store.delete(target);
        } finally {
            store.delete(target);
        }
    }
}
