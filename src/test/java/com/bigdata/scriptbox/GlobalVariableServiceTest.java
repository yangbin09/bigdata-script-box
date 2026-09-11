package com.bigdata.scriptbox;

import com.bigdata.scriptbox.entity.GlobalVariable;
import com.bigdata.scriptbox.service.GlobalVariableService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalVariableServiceTest extends BaseIntegrationTest {

    @Autowired private GlobalVariableService variableService;

    @Test
    void createListAndInject() {
        long n = System.nanoTime();
        variableService.create("DEMO_ENV_" + n, "test", "demo", false, true);
        variableService.create("HDFS_ROOT_" + n, "/tmp/x", "hdfs root", false, true);
        variableService.create("SECRET_KEY_" + n, "real-password", "secret", true, true);
        Map<String, String> env = variableService.envForExecution();
        assertEquals("test", env.get("DEMO_ENV_" + n));
        assertEquals("/tmp/x", env.get("HDFS_ROOT_" + n));
        assertEquals("real-password", env.get("SECRET_KEY_" + n));
    }

    @Test
    void disabledVariablesNotInjected() {
        long n = System.nanoTime();
        variableService.create("A_" + n, "1", "", false, true);
        variableService.create("B_" + n, "2", "", false, false);
        Map<String, String> env = variableService.envForExecution();
        assertEquals("1", env.get("A_" + n));
        assertFalse(env.containsKey("B_" + n));
    }

    @Test
    void summaryMasksSensitive() {
        long n = System.nanoTime();
        variableService.create("API_TOKEN_" + n, "real-token", "secret", true, true);
        var list = variableService.listSummary();
        var entry = list.stream().filter(m -> ("API_TOKEN_" + n).equals(m.get("variableKey"))).findFirst().orElseThrow();
        assertEquals("******", entry.get("variableValue"));
        assertEquals(Boolean.TRUE, entry.get("sensitive"));
    }

    @Test
    void rejectsInvalidKey() {
        Exception ex1 = assertThrows(IllegalArgumentException.class,
                () -> variableService.create("1BAD", "x", "", false, true));
        assertTrue(ex1.getMessage().contains("invalid"));
        Exception ex2 = assertThrows(IllegalArgumentException.class,
                () -> variableService.create("HAS-DASH", "x", "", false, true));
        assertTrue(ex2.getMessage().contains("invalid"));
        Exception ex3 = assertThrows(IllegalArgumentException.class,
                () -> variableService.create("", "x", "", false, true));
        assertTrue(ex3.getMessage().contains("required"));
    }

    @Test
    void rejectsDuplicateKey() {
        long n = System.nanoTime();
        String key = "DUP_" + n;
        variableService.create(key, "x", "", false, true);
        Exception ex = assertThrows(IllegalArgumentException.class,
                () -> variableService.create(key, "y", "", false, true));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    void deleteRemoves() {
        GlobalVariable v = variableService.create("DELME_" + System.nanoTime(), "z", "", false, true);
        variableService.delete(v.getId());
        assertNull(variableService.get(v.getId()));
    }
}