package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSnapshotReaderTest {
    @TempDir Path root;

    private String git(Path cwd, String... args) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        var p =
                new ProcessBuilder(command)
                        .directory(cwd.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output =
                new String(
                        p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as(output).isZero();
        return output.trim();
    }

    @Test
    void readsPinnedMainAndSeesLaterCommitWithoutCheckout() throws Exception {
        Path remote = Files.createDirectory(root.resolve("remote"));
        git(remote, "init", "-b", "main");
        git(remote, "config", "user.name", "Test");
        git(remote, "config", "user.email", "test@example.invalid");
        Files.createDirectory(remote.resolve("semantic"));
        Files.writeString(remote.resolve("semantic/model.yaml"), "version: first\n");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "first");
        var reader =
                new GitSnapshotReader(
                        "git", remote.toString(), root.resolve("mirror"), Duration.ofSeconds(10));
        var first = reader.read();
        assertThat(first.commit()).isEqualTo(git(remote, "rev-parse", "HEAD"));
        assertThat(new String(first.yaml())).isEqualTo("version: first\n");
        assertThat(reader.read().commit()).isEqualTo(first.commit());
        Files.writeString(remote.resolve("semantic/model.yaml"), "version: second\n");
        git(remote, "commit", "-am", "second");
        assertThat(reader.read().commit()).isEqualTo(git(remote, "rev-parse", "HEAD"));
        Files.delete(remote.resolve("semantic/model.yaml"));
        git(remote, "commit", "-am", "remove");
        assertThatThrownBy(reader::read).isInstanceOf(IllegalStateException.class);
        Files.write(remote.resolve("semantic/model.yaml"), new byte[OssieParser.MAX_BYTES + 1]);
        git(remote, "add", ".");
        git(remote, "commit", "-m", "oversized");
        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("大小");
        // 直接建立 Git 符号链接树项，使 Windows 测试无需管理员创建本地符号链接。
        Files.writeString(remote.resolve("semantic/model.yaml"), "outside.yaml");
        String blob = git(remote, "hash-object", "-w", "semantic/model.yaml");
        git(remote, "update-index", "--cacheinfo", "120000," + blob + ",semantic/model.yaml");
        git(remote, "commit", "-m", "symlink");
        assertThatThrownBy(reader::read)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("普通文件");
    }
}
