package dev.arctic.local.config;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.nio.charset.StandardCharsets.UTF_16LE;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Optional;

/**
 * Generic credentials in Windows Credential Manager, through {@code Advapi32}. Secrets are stored as UTF-16LE blobs
 * without a terminator, persisted per user on this machine. Requires {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class WindowsCredentialStore implements SecretStore {

    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final int ERROR_NOT_FOUND = 1168;

    /** {@code CREDENTIALW} on 64-bit Windows. */
    private static final StructLayout CREDENTIAL = MemoryLayout.structLayout(
            JAVA_INT.withName("Flags"),
            JAVA_INT.withName("Type"),
            ADDRESS.withName("TargetName"),
            ADDRESS.withName("Comment"),
            MemoryLayout.structLayout(JAVA_INT, JAVA_INT).withName("LastWritten"),
            JAVA_INT.withName("CredentialBlobSize"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("CredentialBlob"),
            JAVA_INT.withName("Persist"),
            JAVA_INT.withName("AttributeCount"),
            ADDRESS.withName("Attributes"),
            ADDRESS.withName("TargetAlias"),
            ADDRESS.withName("UserName"));
    private static final long TYPE = offset("Type");
    private static final long TARGET_NAME = offset("TargetName");
    private static final long BLOB_SIZE = offset("CredentialBlobSize");
    private static final long BLOB = offset("CredentialBlob");
    private static final long PERSIST = offset("Persist");

    @Override
    public Optional<String> read(String target) {
        return call(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(Advapi32.CALL_STATE);
                MemorySegment out = arena.allocate(ADDRESS);
                int ok = (int) Advapi32.CRED_READ.invokeExact(state, wide(arena, target), CRED_TYPE_GENERIC, 0, out);
                if (ok == 0) {
                    int error = Advapi32.lastError(state);
                    if (error == ERROR_NOT_FOUND) {
                        return Optional.empty();
                    }
                    throw failure("CredReadW", error);
                }
                MemorySegment credential = out.get(ADDRESS, 0).reinterpret(CREDENTIAL.byteSize());
                try {
                    int size = credential.get(JAVA_INT, BLOB_SIZE);
                    byte[] bytes = credential.get(ADDRESS, BLOB).reinterpret(size).toArray(JAVA_BYTE);
                    String secret = new String(bytes, UTF_16LE);
                    Arrays.fill(bytes, (byte) 0);
                    return Optional.of(secret);
                } finally {
                    Advapi32.CRED_FREE.invokeExact(credential);
                }
            }
        });
    }

    @Override
    public void write(String target, String secret) {
        call(() -> {
            byte[] bytes = secret.getBytes(UTF_16LE);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment blob = arena.allocateFrom(JAVA_BYTE, bytes);
                Arrays.fill(bytes, (byte) 0);
                MemorySegment credential = arena.allocate(CREDENTIAL);
                credential.set(JAVA_INT, TYPE, CRED_TYPE_GENERIC);
                credential.set(ADDRESS, TARGET_NAME, wide(arena, target));
                credential.set(JAVA_INT, BLOB_SIZE, (int) blob.byteSize());
                credential.set(ADDRESS, BLOB, blob);
                credential.set(JAVA_INT, PERSIST, CRED_PERSIST_LOCAL_MACHINE);
                MemorySegment state = arena.allocate(Advapi32.CALL_STATE);
                int ok = (int) Advapi32.CRED_WRITE.invokeExact(state, credential, 0);
                blob.fill((byte) 0);
                if (ok == 0) {
                    throw failure("CredWriteW", Advapi32.lastError(state));
                }
            }
            return null;
        });
    }

    @Override
    public void delete(String target) {
        call(() -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment state = arena.allocate(Advapi32.CALL_STATE);
                int ok = (int) Advapi32.CRED_DELETE.invokeExact(state, wide(arena, target), CRED_TYPE_GENERIC, 0);
                if (ok == 0) {
                    int error = Advapi32.lastError(state);
                    if (error != ERROR_NOT_FOUND) {
                        throw failure("CredDeleteW", error);
                    }
                }
            }
            return null;
        });
    }

    private static MemorySegment wide(Arena arena, String value) {
        return arena.allocateFrom(value, UTF_16LE);
    }

    private static IllegalStateException failure(String function, int error) {
        return new IllegalStateException(function + " failed with Windows error " + error);
    }

    private static long offset(String field) {
        return CREDENTIAL.byteOffset(groupElement(field));
    }

    private interface NativeCall<T> {
        T run() throws Throwable;
    }

    private static <T> T call(NativeCall<T> body) {
        try {
            return body.run();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }

    /** Loaded on first use, so the class can be referenced on any platform. */
    private static final class Advapi32 {

        static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
        private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(groupElement("GetLastError"));

        static final MethodHandle CRED_READ;
        static final MethodHandle CRED_WRITE;
        static final MethodHandle CRED_DELETE;
        static final MethodHandle CRED_FREE;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup library = SymbolLookup.libraryLookup("Advapi32", Arena.global());
            Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
            CRED_READ = linker.downcallHandle(library.findOrThrow("CredReadW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS), lastError);
            CRED_WRITE = linker.downcallHandle(library.findOrThrow("CredWriteW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), lastError);
            CRED_DELETE = linker.downcallHandle(library.findOrThrow("CredDeleteW"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT), lastError);
            CRED_FREE = linker.downcallHandle(library.findOrThrow("CredFree"), FunctionDescriptor.ofVoid(ADDRESS));
        }

        static int lastError(MemorySegment state) {
            return (int) LAST_ERROR.get(state, 0L);
        }
    }
}
