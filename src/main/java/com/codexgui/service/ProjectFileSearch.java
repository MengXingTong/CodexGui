package com.codexgui.service;

import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProjectFileSearch {
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
        ".git", ".gradle", ".idea", ".vs", ".vscode", "build", "out", "node_modules", "target",
        ".next", ".cache", "binaries", "deriveddatacache", "intermediate", "saved"
    );

    private ProjectFileSearch() {
    }

    public static List<Candidate> find(Path root, String query, int limit) {
        return filter(list(root), query, limit);
    }

    public static List<Candidate> list(Project project) {
        if (project == null || project.isDisposed() || project.getBasePath() == null) return List.of();
        // 工作区文件不一定属于 Rider 的模块内容根，例如 Unreal 项目的 Content 资源目录。
        return list(Path.of(project.getBasePath()));
    }

    public static List<Candidate> list(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();

        var normalizedRoot = root.toAbsolutePath().normalize();
        var candidates = new ArrayList<Candidate>();
        try {
            Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    // 跳过依赖、缓存和构建产物目录，避免大型项目的候选被生成文件淹没。
                    if (!directory.equals(normalizedRoot) && isSkippedDirectory(directory)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    // 只收集普通文件，目录和特殊文件不作为 @ 引用候选。
                    if (attributes.isRegularFile()) {
                        var relativePath = normalizedRoot.relativize(file).toString().replace('\\', '/');
                        candidates.add(new Candidate(file.toAbsolutePath().normalize(), relativePath, file.getFileName().toString()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    // 单个不可读文件不影响工作区内的其它候选。
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(candidates);
    }

    public static List<Candidate> filter(List<Candidate> candidates, String query, int limit) {
        if (candidates == null || candidates.isEmpty() || limit <= 0) return List.of();
        var normalizedQuery = normalize(query);
        return candidates.stream()
            .map(candidate -> new ScoredCandidate(candidate, score(candidate, normalizedQuery)))
            .filter(candidate -> candidate.score() >= 0)
            .sorted(Comparator.comparingInt(ScoredCandidate::score)
                .thenComparingInt(candidate -> candidate.candidate().displayPath().length())
                .thenComparing(candidate -> candidate.candidate().displayPath(), String.CASE_INSENSITIVE_ORDER))
            .limit(limit)
            .map(ScoredCandidate::candidate)
            .toList();
    }

    private static boolean isSkippedDirectory(Path directory) {
        var name = directory.getFileName();
        return name != null && SKIPPED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static int score(Candidate candidate, String query) {
        if (query.isEmpty()) return 0;
        var path = candidate.displayPath().toLowerCase(Locale.ROOT);
        var name = candidate.name().toLowerCase(Locale.ROOT);
        if (path.equals(query)) return 0;
        if (path.startsWith(query)) return 10 + path.length() - query.length();
        if (name.startsWith(query)) return 30 + name.length() - query.length();

        var pathMatch = path.indexOf(query);
        if (pathMatch >= 0) return 60 + pathMatch;
        var fuzzyScore = fuzzySubsequenceScore(path, query);
        return fuzzyScore < 0 ? -1 : 100 + fuzzyScore;
    }

    private static int fuzzySubsequenceScore(String candidate, String query) {
        var candidateIndex = 0;
        var previousMatch = -1;
        var score = 0;
        for (var queryIndex = 0; queryIndex < query.length(); queryIndex++) {
            var match = candidate.indexOf(query.charAt(queryIndex), candidateIndex);
            if (match < 0) return -1;
            score += previousMatch < 0 ? match : match - previousMatch - 1;
            previousMatch = match;
            candidateIndex = match + 1;
        }
        return score;
    }

    public record Candidate(Path path, String displayPath, String name) {
    }

    private record ScoredCandidate(Candidate candidate, int score) {
    }
}
