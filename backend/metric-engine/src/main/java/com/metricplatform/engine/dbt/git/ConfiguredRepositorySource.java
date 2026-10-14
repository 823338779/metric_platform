package com.metricplatform.engine.dbt.git;

import com.metricplatform.environment.RepositorySource;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只列出配置仓库的远端分支，不克隆、不检出、不执行仓库内容。 */
@Component
public final class ConfiguredRepositorySource implements RepositorySource {
    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private static final int TIMEOUT_SECONDS = 15;
    private static final String HEADS = "refs/heads/";
    private final String repository;

    public ConfiguredRepositorySource(
            @Value("${metric-platform.git.repository}") String repository) {
        URI uri = URI.create(repository);
        if (uri.getScheme() == null
                || !Set.of("http", "https", "ssh").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getUserInfo() != null
                        && (!"ssh".equals(uri.getScheme()) || uri.getUserInfo().contains(":"))) {
            throw new IllegalArgumentException("服务器 Git 仓库必须为不含密码或查询参数的 HTTP(S)/SSH 地址");
        }
        this.repository = repository;
    }

    @Override
    public String repository() {
        return repository;
    }

    @Override
    public List<String> branches() {
        var builder =
                new ProcessBuilder(
                        "git",
                        "-c",
                        "protocol.ext.allow=never",
                        "-c",
                        "protocol.file.allow=never",
                        "ls-remote",
                        "--heads",
                        "--",
                        repository);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GCM_INTERACTIVE", "Never");
        builder.environment().put("GIT_SSH_COMMAND", "ssh -oBatchMode=yes");
        // stderr 可能包含部署凭据，禁止将远端原始错误发送给浏览器。
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var output =
                        executor.submit(
                                () -> {
                                    try (var stream = process.getInputStream()) {
                                        return stream.readNBytes(OUTPUT_LIMIT + 1);
                                    }
                                });
                try {
                    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            || process.exitValue() != 0) {
                        throw new UnavailableException();
                    }
                    byte[] bytes = output.get();
                    if (bytes.length > OUTPUT_LIMIT) throw new UnavailableException();
                    var branches = new TreeSet<String>();
                    for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) {
                        String[] fields = line.split("\t", 2);
                        if (fields.length != 2
                                || !fields[0].matches("[0-9a-f]{40}([0-9a-f]{24})?")
                                || !fields[1].startsWith(HEADS)
                                || fields[1].length() == HEADS.length()) {
                            throw new UnavailableException();
                        }
                        branches.add(fields[1].substring(HEADS.length()));
                    }
                    return List.copyOf(branches);
                } finally {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new UnavailableException();
        } catch (IOException | ExecutionException exception) {
            throw new UnavailableException();
        }
    }
}
