package com.metricplatform.gitsync;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 只从固定远端读取 main，使用本地裸仓库，不检出文件、不执行仓库脚本。 */
public final class GitSnapshotReader {
    public static final String FILE = "semantic/model.yaml";
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
            SIZE = "-s",
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

    public synchronized GitSnapshot read() {
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
            if (!commit.matches(HASH)) throw new IllegalStateException("Git 返回了无效的提交标识");
            String entry = text(run(LS_TREE, commit, SEPARATOR, FILE));
            String[] parts = entry.split("[\\s]+", 4);
            if (parts.length != 4
                    || !Set.of(REGULAR, EXECUTABLE).contains(parts[0])
                    || !parts[2].matches(HASH)
                    || !FILE.equals(parts[3])) throw new IllegalStateException("协议文件缺失或不是普通文件");
            long size = Long.parseLong(text(run(CAT_FILE, SIZE, parts[2])));
            if (size <= 0 || size > OssieParser.MAX_BYTES)
                throw new IllegalStateException("协议文件大小超限");
            return new GitSnapshot(commit, run(CAT_FILE, PRINT, parts[2]));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Git 快照读取失败");
        }
    }

    private byte[] run(String... arguments) throws Exception {
        var command =
                new ArrayList<>(
                        List.of(executable, CONFIG, EXT, CONFIG, HOOKS, CONFIG, FS_MONITOR));
        command.addAll(List.of(arguments));
        var builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().put(TERMINAL, ZERO);
        Process process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var out =
                    executor.submit(
                            () ->
                                    readLimited(
                                            process.getInputStream(),
                                            OssieParser.MAX_BYTES + 1024));
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
