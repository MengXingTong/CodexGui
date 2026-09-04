package com.codexgui.service;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;

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
        ".git", ".gradle", ".idea", "build", "out", "node_modules", "target", ".next", ".cache"
    );

    private ProjectFileSearch() {
    }

    public static List<Candidate> find(Path root, String query, int limit) {
        return filter(list(root), query, limit);
    }

    public static List<Candidate> list(Project project) {
        if (project == null || project.isDisposed() || project.getBasePath() == null) return List.of();
        var root = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        var candidates = new ArrayList<Candidate>();
        // ProjectFileIndex 已遵循模块内容根、排除目录和 IDE ignore 规则，避免手动遍历大型生成目录。
        ProjectFileIndex.getInstance(project).iterateContent(file -> {
            if (file.isDirectory()) return true;
            try {
                var path = file.toNioPath().toAbsolutePath().normalize();
                if (!path.startsWith(root)) return true;
                candidates.add(new Candidate(root.relativize(path).toString().replace('\\', '/'), file.getName()));
            } catch (RuntimeException ignored) {
                // 单个无法转换的 VFS 节点不影响其它候选。
            }
            return true;
        });
        return List.copyOf(candidates);
    }

    public static List<Candidate> list(Path root) {
        if (root == null || !Files.isDirectory(root)) return List.of();

        var candidates = new ArrayList<Candidate>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && isSkippedDirectory(directory)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        var relativePath = root.relativize(file).toString().replace('\\', '/');
                        candidates.add(new Candidate(relativePath, file.getFileName().toString()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    // A single unreadable file must not suppress completions from the rest of the project.
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
                .thenComparingInt(candidate -> candidate.candidate().path().length())
                .thenComparing(candidate -> candidate.candidate().path(), String.CASE_INSENSITIVE_ORDER))
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
        var path = candidate.path().toLowerCase(Locale.ROOT);
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

    public record Candidate(String path, String name) {
    }

    private record ScoredCandidate(Candidate candidate, int score) {
    }
}
