package dev.gate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.gate.annotation.GateController;
import dev.gate.core.Context;
import dev.gate.core.Database;
import dev.gate.core.Logger;
import dev.gate.mapping.GetMapping;
import dev.gate.mapping.PutMapping;

// ── routes.yaml 管理 (GitHub API) ──
@GateController
public class AdminYamlController {

    private static final Logger       logger              = new Logger(AdminYamlController.class);
    private static final ObjectMapper mapper              = dev.gate.core.Json.MAPPER;
    private static final HttpClient   http                = dev.gate.core.Http.CLIENT;
    private static final Pattern YAML_IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private record GitHubPutResult(String commitSha, String newFileSha, boolean shaConflict) {}

    // 環境変数チェック
    private static void requireGitHubEnv() {
        String pat = System.getenv("GITHUB_PAT");
        if (pat == null || pat.isBlank()) {
            throw new IllegalStateException("GITHUB_PAT is not configured");
        }
    }

    // github GET するやつ
    private static Map<String, String> ghGetFile() throws Exception {
        requireGitHubEnv();
        String pat    = System.getenv("GITHUB_PAT");
        String owner  = System.getenv("GITHUB_OWNER");
        String repo   = System.getenv("GITHUB_REPO");
        String branch = System.getenv("GITHUB_BRANCH");
        String path   = System.getenv("GITHUB_YAML_PATH");
        String url    = "https://api.github.com/repos/" + owner + "/" + repo
                      + "/contents/" + path + "?ref=" + branch;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github.v3+json")
            .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new RuntimeException("GitHub GET failed: " + res.statusCode());
        Map<?,?> body   = mapper.readValue(res.body(), Map.class);
        String encoded  = (String) body.get("content");
        String sha      = (String) body.get("sha");
        String content  = new String(java.util.Base64.getMimeDecoder().decode(encoded),
                                     java.nio.charset.StandardCharsets.UTF_8);
        return Map.of("content", content, "sha", sha);
    }

    // github PUT するやつ
    private static GitHubPutResult ghPutFile(String content, String sha, String authorEmail)
            throws Exception {
        requireGitHubEnv();
        String pat    = System.getenv("GITHUB_PAT");
        String owner  = System.getenv("GITHUB_OWNER");
        String repo   = System.getenv("GITHUB_REPO");
        String branch = System.getenv("GITHUB_BRANCH");
        String path   = System.getenv("GITHUB_YAML_PATH");
        String url    = "https://api.github.com/repos/" + owner + "/" + repo
                      + "/contents/" + path;
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String bodyStr = mapper.writeValueAsString(Map.of(
            "message",   "Update routes.yaml by admin panel",
            "content",   encoded,
            "sha",       sha,
            "branch",    branch,
            "committer", Map.of(
                "name",  authorEmail.split("@")[0],
                "email", authorEmail
            )
        ));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github.v3+json")
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(bodyStr)).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 409) return new GitHubPutResult(null, null, true);
        if (res.statusCode() != 200 && res.statusCode() != 201)
            throw new RuntimeException("GitHub PUT failed: " + res.statusCode());
        Map<?,?> parsed  = mapper.readValue(res.body(), Map.class);
        Map<?,?> commit  = (Map<?,?>) parsed.get("commit");
        Map<?,?> fileObj = (Map<?,?>) parsed.get("content");
        return new GitHubPutResult(
            (String) commit.get("sha"),
            (String) fileObj.get("sha"),
            false
        );
    }

    // github actions取得
    private static Map<String, Object> ghGetLatestRun() throws Exception {
        requireGitHubEnv();
        String pat      = System.getenv("GITHUB_PAT");
        String owner    = System.getenv("GITHUB_OWNER");
        String repo     = System.getenv("GITHUB_REPO");
        String branch   = System.getenv("GITHUB_BRANCH");
        String workflow = System.getenv("GITHUB_WORKFLOW_FILE");
        if (workflow == null || workflow.isBlank()) workflow = "deploy.yml";
        // codeQLとかを除外するためのworkflow指定。
        String url = "https://api.github.com/repos/" + owner + "/" + repo
                   + "/actions/workflows/" + workflow + "/runs?branch=" + branch + "&per_page=1";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bearer " + pat)
            .header("Accept", "application/vnd.github.v3+json")
            .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new RuntimeException("GitHub Actions GET failed: " + res.statusCode());
        Map<?,?> parsed = mapper.readValue(res.body(), Map.class);
        @SuppressWarnings("unchecked")
        java.util.List<Map<?,?>> runs = (java.util.List<Map<?,?>>) parsed.get("workflow_runs");
        if (runs == null || runs.isEmpty())
            return Map.of("status", "none", "conclusion", "", "runUrl", "", "startedAt", "");
        Map<?,?> run = runs.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> runMap = (Map<String, Object>) run;
        // GitHub returns null (not absent) for conclusion while in-progress — Map.of rejects null values
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("status",     java.util.Objects.toString(runMap.get("status"),     "unknown"));
        result.put("conclusion", java.util.Objects.toString(runMap.get("conclusion"), ""));
        result.put("runUrl",     java.util.Objects.toString(runMap.get("html_url"),   ""));
        result.put("startedAt",  java.util.Objects.toString(runMap.get("created_at"), ""));
        return result;
    }

    // routes.yaml 読み込みとバリデーション
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> parseAndValidateYaml(String yaml) {
        var loaderOptions = new org.yaml.snakeyaml.LoaderOptions();
        org.yaml.snakeyaml.Yaml parser = new org.yaml.snakeyaml.Yaml(
                new org.yaml.snakeyaml.constructor.SafeConstructor(loaderOptions));
        Map<?,?> doc;
        try {
            doc = parser.load(yaml);
        } catch (Exception e) {
            throw new IllegalArgumentException("YAML parse error: " + e.getMessage());
        }
        if (doc == null || !doc.containsKey("routes"))
            throw new IllegalArgumentException("Missing 'routes' key");
        java.util.List<?> rawRoutes = (java.util.List<?>) doc.get("routes");
        if (rawRoutes == null) throw new IllegalArgumentException("'routes' must be a list");

        Set<String> seenPaths = new java.util.LinkedHashSet<>();
        List<Map<String,Object>> routes = new ArrayList<>();
        for (Object r : rawRoutes) {
            if (!(r instanceof Map<?,?> m))
                throw new IllegalArgumentException("Route entry must be a map");

            String path    = (String) m.get("path");
            String table   = (String) m.get("table");
            Object colsRaw = m.get("columns");

            if (path == null || path.isBlank())
                throw new IllegalArgumentException("Route missing 'path'");
            if (!path.startsWith("/"))
                throw new IllegalArgumentException("Route path must start with '/': " + path);
            if (table == null || table.isBlank())
                throw new IllegalArgumentException("Route '" + path + "' missing 'table'");
            if (colsRaw == null)
                throw new IllegalArgumentException("Route '" + path + "' missing 'columns'");
            if (!(colsRaw instanceof List<?> cols) || cols.isEmpty())
                throw new IllegalArgumentException("Route '" + path + "' 'columns' must be a non-empty list");

            if (!seenPaths.add(path))
                throw new IllegalArgumentException("Duplicate path in routes.yaml: " + path);

            if (!YAML_IDENT.matcher(table).matches())
                throw new IllegalArgumentException("Route '" + path + "' unsafe table name: '" + table + "'");

            List<String> columns = new ArrayList<>();
            for (Object col : cols) {
                String c = String.valueOf(col);
                if (!YAML_IDENT.matcher(c).matches())
                    throw new IllegalArgumentException("Route '" + path + "' unsafe column name: '" + c + "'");
                columns.add(c);
            }

            Map<String,Object> entry = new java.util.LinkedHashMap<>();
            entry.put("path",    path);
            entry.put("table",   table);
            entry.put("columns", columns);
            routes.add(entry);
        }
        return routes;
    }

    // routes.yaml のテーブルとカラムのチェック
    private static void validateRoutesYamlDb(List<Map<String,Object>> routes, Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        for (Map<String,Object> entry : routes) {
            String path  = (String) entry.get("path");
            String table = (String) entry.get("table");
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) entry.get("columns");

            // テーブル存在チェック
            try (ResultSet rs = meta.getTables(null, null, table, new String[]{"TABLE", "VIEW"})) {
                if (!rs.next())
                    throw new IllegalArgumentException(
                        "Route '" + path + "': table '" + table + "' does not exist in database");
            }

            // カラム存在チェック
            Set<String> existingCols = new java.util.LinkedHashSet<>();
            try (ResultSet rs = meta.getColumns(null, null, table, null)) {
                while (rs.next()) existingCols.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            for (String col : columns) {
                if (!existingCols.contains(col.toLowerCase()))
                    throw new IllegalArgumentException(
                        "Route '" + path + "': column '" + col + "' does not exist in table '" + table + "'");
            }
        }
    }

    // githubからroutes.yamlをGET
    @GetMapping("/admin/yaml/routes")
    public void getYamlRoutes(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        try {
            Map<String, String> file = ghGetFile();
            ctx.json(Map.of("content", file.get("content"), "sha", file.get("sha")));
        } catch (IllegalStateException e) {
            logger.warn("getYamlRoutes: GitHub not configured: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", "GitHub not configured: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("getYamlRoutes failed", e);
            ctx.status(502).json(Map.of("error", "GitHub API error"));
        }
    }

    // routes.yamlをgithubにput with 構文チェックなど
    @PutMapping("/admin/yaml/routes")
    public void putYamlRoutes(Context ctx) {
        String email = ctx.getAttribute(CfAccessAuth.ATTR_VERIFIED_EMAIL);
        if (email == null || email.isBlank()) email = "unknown@admin"; // 開発環境（CF_ACCESS_DEV_DISABLE=true）用
        try {
            @SuppressWarnings("unchecked")
            Map<?,?> body = ctx.bodyAs(Map.class);
            if (body == null) { ctx.status(400).json(Map.of("error", "Request body required")); return; }
            String content = (String) body.get("content");
            String sha     = (String) body.get("sha");
            if (content == null || sha == null) {
                ctx.status(400).json(Map.of("error", "content and sha are required"));
                return;
            }
            List<Map<String,Object>> routes = parseAndValidateYaml(content);
            try (Connection conn = Database.getConnection()) {
                validateRoutesYamlDb(routes, conn);
            }
            GitHubPutResult result = ghPutFile(content, sha, email);
            if (result.shaConflict()) {
                ctx.status(409).json(Map.of("error",
                    "Conflict: routes.yaml was modified by another user. Please reload."));
                return;
            }
            ctx.json(Map.of("commitSha", result.commitSha(), "newSha", result.newFileSha()));
        } catch (dev.gate.core.ClientErrorException ce) {
            throw ce;
        } catch (IllegalStateException e) {
            logger.warn("putYamlRoutes: GitHub not configured: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", "GitHub not configured: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("putYamlRoutes failed", e);
            ctx.status(502).json(Map.of("error", "GitHub API error"));
        }
    }

    // github actions取得エンドポイント
    @GetMapping("/admin/yaml/status")
    public void getYamlStatus(Context ctx) {
        ctx.header("Cache-Control", "no-store");
        try {
            ctx.json(ghGetLatestRun());
        } catch (IllegalStateException e) {
            logger.warn("getYamlStatus: GitHub not configured: {}", e.getMessage());
            ctx.status(503).json(Map.of("error", "GitHub not configured: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("getYamlStatus failed", e);
            ctx.status(502).json(Map.of("error", "GitHub API error"));
        }
    }
}
