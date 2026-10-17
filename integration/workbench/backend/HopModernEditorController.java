package com.ruoyi.web.controller.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.workbench.WorkbenchProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Workbench owns permissions, storage, revision locks and audit. Existing
 * independent Modern Java21 service is only an in-memory Apache Hop engine.
 * No Hop Web /hop-ui requests, Tomcat class injection or extra daemon.
 */
@RestController
@RequestMapping("/workbench/modern-editor")
public class HopModernEditorController {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Object WRITE_LOCK = new Object();
    private final WorkbenchProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public HopModernEditorController(WorkbenchProperties config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    public record OpenRequest(String project, String file) {}
    public record SaveRequest(String project, String file, String expectedRevision, JsonNode nodes, JsonNode edges) {}

    @PostMapping("/open")
    @PreAuthorize("@ss.hasPermi('workbench:hop:access')")
    public ResponseEntity<?> open(@RequestBody OpenRequest request) {
        try {
            Path file = requireProjectFile(request.project(), request.file());
            byte[] bytes = readBounded(file);
            ObjectNode body = engineRequest("open",request.project(),request.file(),bytes,null,null);
            ObjectNode response = callEngine(body);
            response.remove("xmlBase64");
            return ResponseEntity.ok(response);
        } catch (BadInput bad) {
            return failure(HttpStatus.BAD_REQUEST,bad.code);
        } catch (IOException unavailable) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE,"hop_project_file_unavailable");
        } catch (Exception exception) {
            return failure(HttpStatus.BAD_GATEWAY,"modern_engine_unavailable");
        }
    }

    @PostMapping("/save")
    @Log(title = "Workbench保存Hop Pipeline", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasRole('admin') or @ss.hasPermi('workbench:hop:edit')")
    public ResponseEntity<?> save(@RequestBody SaveRequest request) {
        if (request == null || request.expectedRevision() == null
            || !request.expectedRevision().matches("[0-9a-f]{64}")
            || request.nodes() == null || !request.nodes().isArray()
            || request.edges() == null || !request.edges().isArray()
            || request.nodes().size() > 1000 || request.edges().size() > 3000)
            return failure(HttpStatus.BAD_REQUEST,"invalid_save_request");
        synchronized (WRITE_LOCK) {
            try {
                Path file = requireProjectFile(request.project(),request.file());
                byte[] original = readBounded(file);
                if (!sha(original).equals(request.expectedRevision()))
                    return failure(HttpStatus.CONFLICT,"pipeline_modified_elsewhere");

                ObjectNode body = engineRequest("save",request.project(),request.file(),
                    original,request.nodes(),request.edges());
                ObjectNode result = callEngine(body);
                String xmlBase64 = result.path("xmlBase64").asText("");
                if (xmlBase64.isEmpty() || xmlBase64.length() > MAX_BYTES * 2)
                    return failure(HttpStatus.BAD_GATEWAY,"invalid_engine_output");
                byte[] updated;
                try { updated = Base64.getDecoder().decode(xmlBase64); }
                catch (IllegalArgumentException ex) { return failure(HttpStatus.BAD_GATEWAY,"invalid_engine_output"); }
                if (updated.length == 0 || updated.length > MAX_BYTES ||
                    !sha(updated).equals(result.path("revision").asText()))
                    return failure(HttpStatus.BAD_GATEWAY,"invalid_engine_revision");

                // CAS must hold immediately before replacement, including external
                // Hop Web classic edits which don't participate in our JVM lock.
                if (!sha(readBounded(file)).equals(request.expectedRevision()))
                    return failure(HttpStatus.CONFLICT,"pipeline_modified_elsewhere");
                atomicSave(file,updated);
                result.remove("xmlBase64");
                return ResponseEntity.ok(result);
            } catch (BadInput bad) {
                return failure(HttpStatus.BAD_REQUEST,bad.code);
            } catch (java.nio.file.AccessDeniedException denied) {
                return failure(HttpStatus.FORBIDDEN,"project_write_denied");
            } catch (IOException io) {
                return failure(HttpStatus.SERVICE_UNAVAILABLE,"hop_project_write_unavailable");
            } catch (Exception error) {
                return failure(HttpStatus.BAD_GATEWAY,"modern_engine_unavailable");
            }
        }
    }

    private ObjectNode engineRequest(String operation,String project,String filename,
                                     byte[] xml,JsonNode nodes,JsonNode edges) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("operation",operation);
        payload.put("id",project+"/"+filename);
        payload.put("revision",sha(xml));
        payload.put("xmlBase64",Base64.getEncoder().encodeToString(xml));
        if (nodes != null) payload.set("nodes",nodes);
        if (edges != null) payload.set("edges",edges);
        return payload;
    }

    private ObjectNode callEngine(ObjectNode payload) throws Exception {
        String token=System.getenv("WORKBENCH_MODERN_ENGINE_TOKEN");
        String url=System.getenv("WORKBENCH_MODERN_ENGINE_URL");
        if (token == null || token.length() < 32 || url == null || url.isBlank())
            throw new IllegalStateException("Workbench internal Hop engine not configured");
        URI endpoint=URI.create(url);
        // Fail closed on unsafe remote endpoints / accidental public fallback.
        if (!"http".equals(endpoint.getScheme())
            || endpoint.getHost() == null || !endpoint.getHost().matches("172\\.(17|18|19|20)\\.0\\.1")
            || !"/api/workbench-internal/engine".equals(endpoint.getPath()))
            throw new IllegalStateException("Internal Hop engine endpoint must use Docker private gateway");
        HttpRequest request=HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(22))
            .header("content-type","application/json")
            .header("X-Workbench-Engine-Token",token)
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.body().length() > MAX_BYTES * 3)
            throw new IOException("Engine response exceeded bounds");
        if (response.statusCode()==403) throw new IOException("Engine authorization rejected request");
        if (response.statusCode()==409) throw new BadInput("engine_revision_mismatch");
        if (response.statusCode()!=200) throw new IOException("Engine refused graph operation");
        JsonNode parsed=mapper.readTree(response.body());
        if (!(parsed instanceof ObjectNode graph) || !graph.path("nodes").isArray()
            || !graph.path("edges").isArray() || !graph.path("revision").isTextual())
            throw new IOException("Invalid engine response");
        return graph;
    }

    private Path requireProjectFile(String project,String file) throws IOException {
        if (project==null || project.isBlank() || project.length()>160 ||
            !project.matches("[\\p{L}\\p{N}._-]+") ||
            project.equals(".") || project.equals("..") ||
            file==null || file.isBlank() || file.length()>600 ||
            file.startsWith("/") || file.contains("\\") ||
            !file.toLowerCase(Locale.ROOT).endsWith(".hpl"))
            throw new BadInput("invalid_project_path");
        Path root=Path.of(config.getHopProjectsPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Hop project root missing");
        Path folder=root.resolve(project);
        if (!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)) throw new BadInput("project_not_found");
        for(String segment:file.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals(".."))
                throw new BadInput("invalid_project_path");
            folder=folder.resolve(segment);
            if (Files.isSymbolicLink(folder)) throw new BadInput("symlink_not_allowed");
        }
        if (!folder.normalize().startsWith(root) || !Files.isRegularFile(folder,LinkOption.NOFOLLOW_LINKS))
            throw new BadInput("pipeline_not_found");
        return folder;
    }

    private byte[] readBounded(Path file) throws IOException {
        if (Files.size(file)>MAX_BYTES) throw new BadInput("pipeline_too_large");
        byte[] content=Files.readAllBytes(file);
        if(content.length>MAX_BYTES)throw new BadInput("pipeline_too_large");
        return content;
    }

    private void atomicSave(Path file,byte[] bytes) throws IOException {
        Path parent=file.getParent();
        Path temp=Files.createTempFile(parent,".anlan-modern-", ".hpl.tmp");
        try {
            Set<PosixFilePermission> permissions=new HashSet<>(Files.getPosixFilePermissions(file));
            // New file is owned by the Workbench UID, but the Hop engine uses
            // GID 501; allow both through setgid project directories.
            permissions.add(PosixFilePermission.GROUP_READ);
            permissions.add(PosixFilePermission.GROUP_WRITE);
            permissions.add(PosixFilePermission.OWNER_READ);
            permissions.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(temp,permissions);
            try(FileChannel ch=FileChannel.open(temp,StandardOpenOption.WRITE)){
                ByteBuffer b=ByteBuffer.wrap(bytes);
                while(b.hasRemaining()) ch.write(b);
                ch.force(true);
            }
            Path backup=file.resolveSibling("."+file.getFileName()+".anlan.bak");
            if(Files.isSymbolicLink(backup))throw new IOException("backup symlink refused");
            Files.copy(file,backup,StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,LinkOption.NOFOLLOW_LINKS);
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static ResponseEntity<Map<String,String>> failure(HttpStatus status,String code) {
        return ResponseEntity.status(status).body(Map.of("code",code));
    }
    private static class BadInput extends IOException {
        final String code;
        BadInput(String code){super(code);this.code=code;}
    }
}