package com.bigdata.scriptbox;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.service.ScriptService;
import com.bigdata.scriptbox.service.SyntaxCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SyntaxCheckTest extends BaseIntegrationTest {

    @Autowired private SyntaxCheckService syntaxCheckService;
    @Autowired private ScriptService scriptService;

    @Test
    void validBodyReturnsOk() {
        String body = "#!/bin/bash\necho hello\nexit 0\n";
        SyntaxCheckService.SyntaxResult r = syntaxCheckService.check(body);
        assertTrue(r.ok, "should pass: " + r.errors);
        assertTrue(r.errors.isEmpty());
    }

    @Test
    void unmatchedIfReturnsError() {
        String body = "#!/bin/bash\nif true; then echo hi\n";
        SyntaxCheckService.SyntaxResult r = syntaxCheckService.check(body);
        assertFalse(r.ok);
        assertFalse(r.errors.isEmpty());
        // bash -n messages usually contain 'syntax error' or line refs.
        String joined = String.join(" ", r.errors);
        assertTrue(joined.toLowerCase().contains("syntax")
                        || joined.matches(".*line.*\\d+.*"),
                "errors should look like bash diagnostics: " + joined);
    }

    @Test
    void unmatchedQuoteReturnsError() {
        String body = "#!/bin/bash\necho 'unterminated\n";
        SyntaxCheckService.SyntaxResult r = syntaxCheckService.check(body);
        assertFalse(r.ok, "unterminated quote should fail");
        assertFalse(r.errors.isEmpty());
    }

    @Test
    void emptyBodyReturnsOk() {
        // Empty bodies are sometimes acceptable for placeholder scripts.
        SyntaxCheckService.SyntaxResult r = syntaxCheckService.check("");
        assertTrue(r.ok, "empty body should pass: " + r.errors);
    }

    @Test
    void shebangNotRequiredForOk() {
        String body = "echo hi\n";
        SyntaxCheckService.SyntaxResult r = syntaxCheckService.check(body);
        assertTrue(r.ok, "no-shebang body should pass bash -n");
    }

    @Test
    void createRejectsBadBody() {
        Script s = new Script();
        s.setName("bad-syntax-" + System.nanoTime());
        s.setDisplayName("bad-syntax");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        String bad = "#!/bin/bash\nif true; then echo unterminated\n";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> scriptService.create(s, new InMemoryMultipartFile(
                        "file", "bad.sh", "application/x-sh",
                        bad.getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().toLowerCase().contains("syntax")
                        || ex.getMessage().toLowerCase().contains("check"),
                "should surface syntax-check failure: " + ex.getMessage());
    }

    @Test
    void saveScriptBodyRejectsBadBody() throws Exception {
        Script s = new Script();
        s.setName("save-bad-" + System.nanoTime());
        s.setDisplayName("save-bad");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        String good = "#!/bin/bash\necho ok\n";
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "ok.sh", "application/x-sh",
                good.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(saved.getId());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> scriptService.saveScriptBody(saved.getId(),
                        "#!/bin/bash\nif true; then oops\n"));
        assertTrue(ex.getMessage().toLowerCase().contains("syntax"));
    }

    @Test
    void updateWithBadFileRejected() throws Exception {
        Script s = new Script();
        s.setName("upd-bad-" + System.nanoTime());
        s.setDisplayName("upd-bad");
        s.setCategory("Mock");
        s.setTimeoutSeconds(60);
        s.setEnabled(true);
        String good = "#!/bin/bash\necho ok\n";
        Script saved = scriptService.create(s, new InMemoryMultipartFile(
                "file", "ok.sh", "application/x-sh",
                good.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(saved.getId());

        Script update = new Script();
        update.setId(saved.getId());
        update.setName(saved.getName());
        update.setDisplayName(saved.getDisplayName());
        update.setCategory(saved.getCategory());
        update.setTimeoutSeconds(60);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> scriptService.update(update, new InMemoryMultipartFile(
                        "file", "broken.sh", "application/x-sh",
                        "#!/bin/bash\nif true; then echo unterminated\n".getBytes(StandardCharsets.UTF_8))));
        assertTrue(ex.getMessage().toLowerCase().contains("syntax"));
    }

    @Test
    void syntaxResultToMapSerialisesCleanly() {
        SyntaxCheckService.SyntaxResult r = syntaxCheckService.check("#!/bin/bash\necho ok\n");
        java.util.Map<String, Object> m = r.toMap();
        assertEquals(true, m.get("ok"));
        assertNotNull(m.get("errors"));
        assertNotNull(m.get("warnings"));
        assertTrue(m.get("errors") instanceof java.util.List);
    }

    @Test
    void preflightSyntaxReturnsResultWithoutPersisting() {
        // The facade through ScriptService returns the same shape — just verify
        // it does NOT throw and surfaces errors as values.
        SyntaxCheckService.SyntaxResult good = scriptService.preflightSyntax(
                "#!/bin/bash\necho ok\n");
        assertTrue(good.ok, "good body should pass: " + good.errors);

        SyntaxCheckService.SyntaxResult bad = scriptService.preflightSyntax(
                "#!/bin/bash\nif true; then echo unterminated\n");
        assertFalse(bad.ok, "bad body should fail without throwing");
        assertFalse(bad.errors.isEmpty());
    }

    @Test
    void preflightSyntaxEmptyBodyReturnsOk() {
        SyntaxCheckService.SyntaxResult r = scriptService.preflightSyntax("");
        assertTrue(r.ok, "empty body should pass: " + r.errors);
    }
}