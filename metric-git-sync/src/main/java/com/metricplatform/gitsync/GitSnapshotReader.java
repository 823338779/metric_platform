package com.metricplatform.gitsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 只从固定远端读取 main，使用本地裸仓库，不检出文件、不执行仓库脚本。 */
public final class GitSnapshotReader {
    private static final String INIT = "init",
            BARE = "--bare",
            FETCH = "fetch",
            NO_TAGS = "--no-tags",
            SEPARATOR = "--",
            MAIN = "refs/heads/main",
            FETCH_HEAD = "FETCH_HEAD",
            REV_PARSE = "rev-parse",
            VERIFY = "--verify",
            LS_TREE = "ls-tree",
            CAT_FILE = "cat-file",
            PRINT = "-p",
            HEAD = "HEAD",
            CONFIG = "-c",
            EXT = "protocol.ext.allow=never",
            HOOKS = "core.hooksPath=/dev/null",
            FS_MONITOR = "core.fsmonitor=false",
            TERMINAL = "GIT_TERMINAL_PROMPT",
            ZERO = "0",
            REGULAR = "100644",
            EXECUTABLE = "100755";
    private static final String HASH = "[0-9a-f]{40}([0-9a-f]{24})?";
    private static final String PROJECT_FILE = "dbt_project.yml",
            RECURSIVE = "-r",
            NUL_TERMINATED = "-z",
            BLOB = "blob",
            SHA256 = "SHA-256",
            PROJECT_ERROR = "dbt 项目输入无效",
            PATH_ERROR = "dbt 项目路径无效";
    private static final int TREE_LIMIT = 8 * 1024 * 1024;
    private static final Set<String> ROOT_INPUTS =
            Set.of(
                    PROJECT_FILE,
                    "packages.yml",
                    "package-lock.yml",
                    "dependencies.yml",
                    "selectors.yml");
    private static final Set<String> INPUT_DIRECTORIES =
            Set.of("models", "macros", "tests", "analyses", "seeds", "snapshots");
    private static final Map<String, String> RESOURCE_PATHS =
            Map.of(
                    "model-paths", "models",
                    "macro-paths", "macros",
                    "test-paths", "tests",
                    "analysis-paths", "analyses",
                    "seed-paths", "seeds",
                    "snapshot-paths", "snapshots");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final String executable, remote;
    private final Path directory;
    private final Duration timeout;

    public GitSnapshotReader(String executable, String remote, Path directory, Duration timeout) {
        if (remote == null
                || remote.isBlank()
                || timeout == null
                || timeout.isZero()
                || timeout.isNegative()) throw new IllegalArgumentException("Git 配置不完整");
        this.executable = executable;
        this.remote = remote;
        this.directory = directory.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    public synchronized DbtProjectRevision readDbtRevision(String projectSubdir) {
        String prefix = validateProjectSubdir(projectSubdir);
        try {
            if (!Files.exists(directory.resolve(HEAD))) {
                if (Files.exists(directory))
                    try (var entries = Files.list(directory)) {
                        if (entries.findAny().isPresent())
                            throw new IllegalStateException("Git 缓存目录必须为空或为专用裸仓库");
                    }
                Files.createDirectories(directory);
                run(INIT, BARE);
            }
            run(FETCH, NO_TAGS, SEPARATOR, remote, MAIN);
            String commit = text(run(REV_PARSE, VERIFY, FETCH_HEAD));
            if (!commit.matches(HASH)) throw new IllegalStateException(PROJECT_ERROR);

            // 只读取 Git 树对象的元数据，避免 checkout 和仓库脚本执行。
            byte[] tree =
                    prefix.isEmpty()
                            ? run(TREE_LIMIT, LS_TREE, RECURSIVE, NUL_TERMINATED, commit)
                            : run(
                                    TREE_LIMIT,
                                    LS_TREE,
                                    RECURSIVE,
                                    NUL_TERMINATED,
                                    commit,
                                    SEPARATOR,
                                    prefix);
            var inputs = new TreeMap<String, String>();
            String projectBlob = null;
            for (String raw : new String(tree, StandardCharsets.UTF_8).split("\u0000")) {
                if (raw.isEmpty()) continue;
                int tab = raw.indexOf('\t');
                if (tab <= 0) throw new IllegalStateException(PROJECT_ERROR);
                String[] metadata = raw.substring(0, tab).split(" ");
                String path = raw.substring(tab + 1);
                if (metadata.length != 3
                        || !BLOB.equals(metadata[1])
                        || !metadata[2].matches(HASH)
                        || !Set.of(REGULAR, EXECUTABLE).contains(metadata[0]))
                    throw new IllegalStateException(PROJECT_ERROR);
                String relative = prefix.isEmpty() ? path : path.substring(prefix.length() + 1);
                if (relative.equals(PROJECT_FILE)) projectBlob = metadata[2];
                if (isDbtInput(relative)) inputs.put(relative, metadata[0] + " " + metadata[2]);
            }
            if (projectBlob == null) throw new IllegalStateException(PROJECT_ERROR);
            byte[] projectYaml = run(CAT_FILE, PRINT, projectBlob);
            validateResourcePaths(projectYaml);

            MessageDigest digest = MessageDigest.getInstance(SHA256);
            for (var entry : inputs.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return new DbtProjectRevision(commit, HexFormat.of().formatHex(digest.digest()));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(PROJECT_ERROR);
        }
    }

    private static String validateProjectSubdir(String projectSubdir) {
        if (projectSubdir == null || projectSubdir.isBlank())
            throw new IllegalArgumentException(PATH_ERROR);
        if (".".equals(projectSubdir)) return "";
        if (projectSubdir.startsWith("/")
                || projectSubdir.endsWith("/")
                || projectSubdir.contains("\\")
                || projectSubdir.contains(":")) throw new IllegalArgumentException(PATH_ERROR);
        for (String segment : projectSubdir.split("/"))
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment))
                throw new IllegalArgumentException(PATH_ERROR);
        return projectSubdir;
    }

    private static boolean isDbtInput(String relative) {
        if (ROOT_INPUTS.contains(relative)) return true;
        int slash = relative.indexOf('/');
        return slash > 0 && INPUT_DIRECTORIES.contains(relative.substring(0, slash));
    }

    private static void validateResourcePaths(byte[] projectYaml) {
        try {
            JsonNode project = YAML.readTree(projectYaml);
            if (project == null || !project.isObject())
                throw new IllegalStateException(PROJECT_ERROR);
            for (var entry : RESOURCE_PATHS.entrySet()) {
                JsonNode paths = project.get(entry.getKey());
                if (paths == null) continue;
                if (!paths.isArray()
                        || paths.size() != 1
                        || !entry.getValue().equals(paths.get(0).asText()))
                    throw new IllegalStateException(PROJECT_ERROR);
            }
        } catch (Exception e) {
            throw new IllegalStateException(PROJECT_ERROR);
        }
    }

    private byte[] run(String... arguments) throws Exception {
        return run(TREE_LIMIT, arguments);
    }

    private byte[] run(int limit, String... arguments) throws Exception {
        var command =
                new ArrayList<>(
                        List.of(executable, CONFIG, EXT, CONFIG, HOOKS, CONFIG, FS_MONITOR));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().put(TERMINAL, ZERO);
        Process process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var out = executor.submit(() -> readLimited(process.getInputStream(), limit));
            var err = executor.submit(() -> readLimited(process.getErrorStream(), 16384));
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("Git 命令超时");
                byte[] result = out.get();
                err.get();
                // stderr 可能包含远端凭据或路径，只报告固定错误，不写入平台状态或接口响应。
                if (process.exitValue() != 0) throw new IllegalStateException("Git 命令执行失败");
                return result;
            } finally {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static byte[] readLimited(InputStream stream, int limit) throws Exception {
        try (stream) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalStateException("Git 输出超限");
            return bytes;
        }
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }
}
