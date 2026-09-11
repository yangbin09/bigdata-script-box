package com.bigdata.scriptbox.controller;

import com.bigdata.scriptbox.dto.ApiResponse;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.service.ScriptPackageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/scripts")
public class ScriptPackageController {

    @Autowired private ScriptPackageService packageService;

    @GetMapping(value = "/{id}/export", produces = "application/zip")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        try {
            byte[] bytes = packageService.export(id);
            Script s = packageService.export(id) == null ? null : null;
            // Fetch name for the filename header
            String name = "script-" + id + ".zip";
            try {
                name = packageService.exportFilename(id);
            } catch (Exception ignored) {}
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + name + "\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(bytes);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (IOException ex) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ScriptPackageService.ImportResult> doImport(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "overwrite", required = false, defaultValue = "false") boolean overwrite) {
        try {
            return ApiResponse.ok(packageService.doImport(file, overwrite));
        } catch (IllegalArgumentException ex) {
            return ApiResponse.error(ex.getMessage());
        } catch (IOException ex) {
            return ApiResponse.error("import failed: " + ex.getMessage());
        }
    }
}