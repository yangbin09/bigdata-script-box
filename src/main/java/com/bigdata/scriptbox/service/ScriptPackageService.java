package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Script package import/export: produce and consume a ZIP file containing
 *   manifest.json
 *   script.sh
 *   params.json
 *
 * Defenses:
 *   - ZIP Slip: every entry name is normalised and must resolve inside the
 *     target extraction directory.
 *   - Path traversal: name must not contain ".." or absolute path components.
 *   - Size limits: each entry <= 1 MB; total script body <= maxScriptBytes.
 */
@Service
public class ScriptPackageService {

    private static final Logger log = LoggerFactory.getLogger(ScriptPackageService.class);
    private static final long MAX_ENTRY_BYTES = 1024L * 1024L; // 1 MB per entry

    @Autowired private ScriptBoxProperties props;
    @Autowired private ScriptService scriptService;
    @Autowired private ScriptVersionService versionService;

    private final ObjectMapper mapper = new ObjectMapper();

    public byte[] export(Long scriptId) throws IOException {
        Script s = scriptService.getById(scriptId);
        if (s == null) throw new IllegalArgumentException("script not found: " + scriptId);
        String body = scriptService.readScriptBody(scriptId);
        List<ScriptParam> params = scriptService.paramsOf(scriptId);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("packageVersion", "1.0");
        manifest.put("name", s.getName());
        manifest.put("displayName", s.getDisplayName());
        manifest.put("category", s.getCategory());
        manifest.put("description", s.getDescription());
        manifest.put("timeoutSeconds", s.getTimeoutSeconds());
        manifest.put("enabled", s.getEnabled());
        // precheckConfigJson is meta about environment, not tenant/keytab state;
        // safe to round-trip in the package.
        manifest.put("precheckConfigJson", s.getPrecheckConfigJson());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            putJsonEntry(zos, "manifest.json", mapper.writeValueAsString(manifest));
            putJsonEntry(zos, "params.json",
                    mapper.writeValueAsString(toParamMapList(params)));
            putEntry(zos, "script.sh", body == null ? "" : body);
        }
        return baos.toByteArray();
    }

    /** Compute the export filename from a script id; used by the controller. */
    public String exportFilename(Long scriptId) {
        Script s = scriptService.getById(scriptId);
        if (s == null) return "script-" + scriptId + ".zip";
        String safe = s.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        return safe + "-" + scriptId + ".zip";
    }

    /** Result of an import. */
    public static class ImportResult {
        public Script script;
        public String name;             // name used (after de-dup)
        public boolean created;         // true if a new row was inserted
        public boolean copied;          // true if the supplied name collided and we created a copy
    }

    /**
     * Import a script package. ZIP entries must be exactly manifest.json, script.sh,
     * params.json. Names colliding with existing scripts are auto-suffixed with "-copy-N".
     */
    public ImportResult doImport(MultipartFile file, boolean overwrite) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("empty zip");
        if (file.getSize() > props.getMaxScriptBytes() * 4)
            throw new IllegalArgumentException("zip too large");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/") || name.contains("\\"))
                    throw new IllegalArgumentException("invalid entry name: " + name);
                // Reject absolute paths or parent-relative components even after name() parsing.
                Path normalised = Paths.get(name).normalize();
                if (normalised.startsWith("..") || normalised.isAbsolute())
                    throw new IllegalArgumentException("zip slip attempt: " + name);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                long total = 0;
                int r;
                while ((r = zis.read(buf)) > 0) {
                    total += r;
                    if (total > MAX_ENTRY_BYTES)
                        throw new IllegalArgumentException("entry too large: " + name);
                    baos.write(buf, 0, r);
                }
                entries.put(name, baos.toByteArray());
            }
        }

        if (!entries.containsKey("manifest.json"))
            throw new IllegalArgumentException("missing manifest.json");
        if (!entries.containsKey("script.sh"))
            throw new IllegalArgumentException("missing script.sh");

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = mapper.readValue(entries.get("manifest.json"), Map.class);
        String originalName = String.valueOf(manifest.getOrDefault("name", "imported"));
        String displayName = String.valueOf(manifest.getOrDefault("displayName", originalName));
        String category = (String) manifest.getOrDefault("category", "");
        String description = (String) manifest.getOrDefault("description", "");
        Object to = manifest.get("timeoutSeconds");
        Integer timeout = to instanceof Number n ? n.intValue() : 600;
        Object en = manifest.get("enabled");
        boolean enabled = en == null ? Boolean.TRUE : Boolean.TRUE.equals(en);
        String precheckCfg = manifest.get("precheckConfigJson") == null
                ? null : String.valueOf(manifest.get("precheckConfigJson"));

        String body = new String(entries.get("script.sh"), StandardCharsets.UTF_8);
        List<ScriptParam> params = parseParamsJson(entries.get("params.json"));

        ImportResult result = new ImportResult();
        // Resolve name conflict
        final String baseName = originalName;
        String name = baseName;
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (Script x : scriptService.listAll()) existingNames.add(x.getName());
        boolean nameTaken = existingNames.contains(name);
        if (nameTaken && !overwrite) {
            int n = 1;
            while (existingNames.contains(name)) {
                name = baseName + "-copy-" + n;
                n++;
            }
            result.copied = true;
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Script s = new Script();
        s.setName(name);
        s.setDisplayName(displayName + (result.copied ? " - Copy" : ""));
        s.setCategory(category);
        s.setDescription(description);
        s.setTimeoutSeconds(timeout);
        s.setEnabled(result.copied ? Boolean.FALSE : enabled);
        s.setFavorite(Boolean.FALSE);
        s.setPrecheckConfigJson(precheckCfg);
        InMemoryMultipartFile mf = new InMemoryMultipartFile(
                "file", name + ".sh", "application/x-sh", bytes);
        Script saved = scriptService.create(s, mf);
        // Re-apply precheck (create() doesn't persist it on the row).
        if (precheckCfg != null && !precheckCfg.isBlank()) {
            saved.setPrecheckConfigJson(precheckCfg);
            saved.setUpdateTime(java.time.LocalDateTime.now());
            scriptService.getMapper().updateById(saved);
        }
        if (!params.isEmpty()) scriptService.replaceParams(saved.getId(), params);

        result.script = saved;
        result.name = saved.getName();
        result.created = true;
        return result;
    }

    /** Parse a params.json blob into a list of ScriptParam. Tolerant of malformed input. */
    private List<ScriptParam> parseParamsJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return List.of();
        try {
            String text = new String(bytes, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) return List.of();
            List<Map<String, Object>> raw = mapper.readValue(text,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            List<ScriptParam> out = new ArrayList<>();
            int i = 0;
            for (Map<String, Object> r : raw) {
                ScriptParam p = new ScriptParam();
                p.setName(String.valueOf(r.getOrDefault("name", "")));
                p.setLabel((String) r.getOrDefault("label", null));
                Object t = r.get("type");
                p.setType(t == null ? "text" : String.valueOf(t));
                p.setDefaultValue((String) r.getOrDefault("defaultValue", null));
                p.setOptions((String) r.getOrDefault("options", null));
                Object req = r.get("required");
                p.setRequired(Boolean.TRUE.equals(req));
                p.setSortOrder(i++);
                p.setPlaceholder((String) r.getOrDefault("placeholder", null));
                p.setHelpText((String) r.getOrDefault("helpText", null));
                if (p.getName() != null && !p.getName().isBlank()) out.add(p);
            }
            return out;
        } catch (Exception ex) {
            log.warn("params.json parse failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> toParamMapList(List<ScriptParam> params) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScriptParam p : params) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("label", p.getLabel());
            m.put("type", p.getType());
            m.put("defaultValue", p.getDefaultValue());
            m.put("options", p.getOptions());
            m.put("required", Boolean.TRUE.equals(p.getRequired()));
            m.put("sortOrder", p.getSortOrder());
            m.put("placeholder", p.getPlaceholder());
            m.put("helpText", p.getHelpText());
            out.add(m);
        }
        return out;
    }

    private void putJsonEntry(ZipOutputStream zos, String name, String content) throws IOException {
        putEntry(zos, name, content);
    }

    private void putEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}