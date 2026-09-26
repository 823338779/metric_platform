package com.metricplatform.gitsync;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitSnapshotReaderTest {
    @TempDir Path root;

    private static final String PROJECT = "project";
    private static final String PROJECT_FILE = "project/dbt_project.yml";
    private static final String MODEL_FILE = "project/models/orders.yml";
    private static final String DOC_FILE = "project/models/docs.md";
    private static final String README_FILE = "project/README.md";
    private static final String CONFIG = "name: sales\nversion: '1.0'\nconfig-version: 2\n";

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

    private Path dbtRemote() throws Exception {
        Path remote = Files.createDirectory(root.resolve("dbt-remote"));
        git(remote, "init", "-b", "main");
        git(remote, "config", "user.name", "Test");
        git(remote, "config", "user.email", "test@example.invalid");
        Files.createDirectories(remote.resolve("project/models"));
        Files.writeString(remote.resolve(PROJECT_FILE), CONFIG);
        Files.writeString(remote.resolve(MODEL_FILE), "models: []\n");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "first");
        return remote;
    }

    private GitSnapshotReader dbtReader(Path remote) {
        return new GitSnapshotReader(
                "git", remote.toString(), root.resolve("dbt-mirror"), Duration.ofSeconds(10));
    }

    @Test
    void readmeOnlyPushKeepsDigest() throws Exception {
        Path remote = dbtRemote();
        var reader = dbtReader(remote);
        var first = reader.readDbtRevision(PROJECT);
        Files.writeString(remote.resolve(README_FILE), "project notes\n");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "readme");

        var second = reader.readDbtRevision(PROJECT);
        assertThat(second.sha()).isNotEqualTo(first.sha());
        assertThat(second.projectDigest()).isEqualTo(first.projectDigest());
    }

    @Test
    void modelYamlChangeChangesDigest() throws Exception {
        Path remote = dbtRemote();
        var reader = dbtReader(remote);
        var first = reader.readDbtRevision(PROJECT);
        Files.writeString(remote.resolve(MODEL_FILE), "models:\n  - name: orders\n");
        git(remote, "commit", "-am", "model");

        assertThat(reader.readDbtRevision(PROJECT).projectDigest())
                .isNotEqualTo(first.projectDigest());
    }

    @Test
    void sourceUsesBuildShaNotCurrentMain() throws Exception {
        Path remote = dbtRemote();
        var reader = dbtReader(remote);
        String first = reader.readDbtRevision(PROJECT).sha();
        Files.writeString(remote.resolve(MODEL_FILE), "models:\n  - name: changed\n");
        git(remote, "commit", "-am", "changed");
        assertThat(reader.readYamlAtSha(PROJECT, first, "models/orders.yml"))
                .isEqualTo("models: []\n");
        assertThatThrownBy(() -> reader.readYamlAtSha(PROJECT, first, "../outside.yml"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelDocsMarkdownChangeChangesDigest() throws Exception {
        Path remote = dbtRemote();
        var reader = dbtReader(remote);
        var first = reader.readDbtRevision(PROJECT);
        Files.writeString(remote.resolve(DOC_FILE), "{% docs orders %}orders{% enddocs %}\n");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "docs");

        assertThat(reader.readDbtRevision(PROJECT).projectDigest())
                .isNotEqualTo(first.projectDigest());
    }

    @Test
    void rejectsSymlinkAndCustomResourcePath() throws Exception {
        Path remote = dbtRemote();
        var reader = dbtReader(remote);
        Files.writeString(remote.resolve(MODEL_FILE), "../outside.yml");
        String blob = git(remote, "hash-object", "-w", MODEL_FILE);
        git(remote, "update-index", "--cacheinfo", "120000," + blob + "," + MODEL_FILE);
        git(remote, "commit", "-m", "symlink");
        assertThatThrownBy(() -> reader.readDbtRevision(PROJECT))
                .isInstanceOf(IllegalStateException.class);

        git(remote, "rm", "--cached", MODEL_FILE);
        Files.delete(remote.resolve(MODEL_FILE));
        Files.writeString(remote.resolve(PROJECT_FILE), CONFIG + "model-paths: ['../outside']\n");
        git(remote, "add", ".");
        git(remote, "commit", "-m", "custom-path");
        assertThatThrownBy(() -> reader.readDbtRevision(PROJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(remote.toString());
    }
}
