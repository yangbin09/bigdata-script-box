package com.bigdata.scriptbox.service;

import com.bigdata.scriptbox.config.InMemoryMultipartFile;
import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.ScriptParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
 * 脚本包（package）导入导出：生成 / 解析一个 ZIP 文件，包含
 * <pre>
 *   manifest.json   元信息（name / displayName / category / ...）
 *   script.sh       脚本正文
 *   params.json     形参定义
 * </pre>
 *
 * <p>防御措施：
 * <ul>
 *   <li>ZIP Slip：每个 entry 名都归一化，且必须落在解压目录内；</li>
 *   <li>路径穿越：entry 名不允许 {@code ..} 或绝对路径前缀；</li>
 *   <li>大小限制：单 entry ≤ 1 MB；脚本正文 ≤ {@link ScriptBoxProperties#getMaxScriptBytes()}。</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ScriptPackageService {

    private static final long MAX_ENTRY_BYTES = 1024L * 1024L; // 每个 entry 1 MB

    private final ScriptBoxProperties props;
    private final ScriptService scriptService;
    private final ScriptVersionService versionService;
    private final ObjectMapper mapper;

    /**
     * 把指定脚本打包成 ZIP 字节流（含 manifest.json / script.sh / params.json）。
     */
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
        // precheckConfigJson 是环境元信息，不涉及租户 / keytab 状态，包往返安全
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

    /**
     * 推导导出文件名（基于脚本 name + id）。非法字符替换为下划线。
     */
    public String exportFilename(Long scriptId) {
        Script s = scriptService.getById(scriptId);
        if (s == null) return "script-" + scriptId + ".zip";
        String safe = s.getName().replaceAll("[^A-Za-z0-9._-]", "_");
        return safe + "-" + scriptId + ".zip";
    }

    /** 导入结果（成功 / 是否新建 / 是否因重名复制）。 */
    public static class ImportResult {
        public Script script;
        public String name;             // 实际使用的 name（去重后）
        public boolean created;         // 是否新建了一行
        public boolean copied;          // 是否因为重名被加了 "-copy-N" 后缀
    }

    /**
     * 导入一个脚本包。ZIP 必须恰好包含 manifest.json / script.sh / params.json。
     * 与现有脚本重名会自动加 "-copy-N" 后缀（除非 {@code overwrite=true}，当前实现
     * 总是非覆盖，新增副本）。
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
                // 即便 names() 已经过滤，也再 normalize 一次确保安全
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
        // 重新落 precheckConfigJson（create() 默认不会回填这个字段）
        if (precheckCfg != null && !precheckCfg.isBlank()) {
            saved.setPrecheckConfigJson(precheckCfg);
            saved.setUpdateTime(java.time.LocalDateTime.now());
            scriptService.getMapper().updateById(saved);
        }
        if (!params.isEmpty()) scriptService.replaceParams(saved.getId(), params);

        result.script = saved;
        result.name = saved.getName();
        result.created = true;
        log.info("package: 导入脚本 name={} copied={}", saved.getName(), result.copied);
        return result;
    }

    /** 把 params.json 解析为 ScriptParam 列表；容错处理格式异常（返回空列表）。 */
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

    /** ScriptParam → Map，导出时用。 */
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