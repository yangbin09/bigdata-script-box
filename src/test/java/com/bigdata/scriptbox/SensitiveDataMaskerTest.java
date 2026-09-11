package com.bigdata.scriptbox;

import com.bigdata.scriptbox.entity.GlobalVariable;
import com.bigdata.scriptbox.service.SensitiveDataMasker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SensitiveDataMasker}.
 *
 * <p>Coverage:
 * <ol>
 *   <li>{@code maskEnv} replaces only declared sensitive globals, never
 *       the env key, and never {@code null} values.</li>
 *   <li>{@code maskCommand} masks {@code --password/--token/--secret/--keytab-path/--credential}
 *       adjacent values but leaves benign options untouched.</li>
 *   <li>{@code maskCommandList} mirrors {@code maskCommand} for the
 *       {@code ProcessBuilder(List<String>)} form.</li>
 * </ol>
 */
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    private GlobalVariable v(String key, String value, boolean sensitive) {
        GlobalVariable g = new GlobalVariable();
        g.setVariableKey(key);
        g.setVariableValue(value);
        g.setSensitive(sensitive);
        return g;
    }

    @Test
    void maskEnvReplacesOnlySensitiveValues() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PUBLIC_KEY", "public-value");
        env.put("DB_PASS", "real-password");

        List<GlobalVariable> declared = List.of(
                v("DB_PASS", "real-password", true),
                v("PUBLIC_KEY", "public-value", false)
        );

        Map<String, String> masked = masker.maskEnv(env, declared);
        assertEquals("******", masked.get("DB_PASS"));
        assertEquals("public-value", masked.get("PUBLIC_KEY"));
        // The original map must not be mutated.
        assertEquals("real-password", env.get("DB_PASS"));
    }

    @Test
    void maskEnvOnNullOrEmptyReturnsEmptyOrUnchanged() {
        assertTrue(masker.maskEnv(null, null).isEmpty());
        assertTrue(masker.maskEnv(new LinkedHashMap<>(), null).isEmpty());
    }

    @Test
    void maskEnvWithNoSensitiveGlobalsIsIdentity() {
        Map<String, String> env = Map.of("A", "1", "B", "2");
        List<GlobalVariable> declared = List.of(v("A", "1", false));
        Map<String, String> masked = masker.maskEnv(env, declared);
        assertEquals(env, masked);
    }

    @Test
    void maskCommandStringReplacesSensitivePairs() {
        String input = "bash script.sh --user alice --password s3cret --token abc123 --name demo";
        String out = masker.maskCommand(input);
        assertFalse(out.contains("s3cret"), "password value must not appear");
        assertFalse(out.contains("abc123"), "token value must not appear");
        assertTrue(out.contains("alice"), "non-sensitive value must remain");
        assertTrue(out.contains("demo"), "non-sensitive value must remain");
        assertTrue(out.contains(SensitiveDataMasker.MASK), "must produce mask placeholder");
    }

    @Test
    void maskCommandStringCaseInsensitive() {
        String input = "bash script.sh --PASSWORD upper";
        String out = masker.maskCommand(input);
        assertFalse(out.contains("upper"));
        assertTrue(out.contains(SensitiveDataMasker.MASK));
    }

    @Test
    void maskCommandStringKeytabPath() {
        String input = "kinit --keytab-path /etc/security/keytabs/admin.headless.keytab alice@EXAMPLE.COM";
        String out = masker.maskCommand(input);
        assertFalse(out.contains("/etc/security/keytabs/admin.headless.keytab"));
        assertTrue(out.contains(SensitiveDataMasker.MASK));
    }

    @Test
    void maskCommandListReplacesSensitiveAdjacentValues() {
        List<String> cmd = new ArrayList<>(List.of(
                "bash", "script.sh",
                "--user", "alice",
                "--password", "p4ssw0rd",
                "--token", "tok-xyz",
                "--verbose"
        ));
        List<String> out = masker.maskCommandList(cmd);
        assertEquals("bash", out.get(0));
        assertEquals("script.sh", out.get(1));
        assertEquals("--user", out.get(2));
        assertEquals("alice", out.get(3));
        assertEquals(SensitiveDataMasker.MASK, out.get(5)); // password value
        assertEquals(SensitiveDataMasker.MASK, out.get(7)); // token value
        assertEquals("--verbose", out.get(8));
        // Original list must not be mutated.
        assertEquals("p4ssw0rd", cmd.get(5));
    }

    @Test
    void maskCommandListEmptyIsNoop() {
        // The implementation returns null/empty for null/empty inputs by reference.
        assertNull(masker.maskCommandList(null));
        assertTrue(masker.maskCommandList(List.of()).isEmpty());
    }
}