package com.codexgui.service;

import com.codexgui.model.ChangeEntry;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class UnifiedDiffParser {
    private static final Pattern HUNK_HEADER = Pattern.compile(
        "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    private UnifiedDiffParser() {}

    static Map<String, String> split(String diff) {
        var result = new LinkedHashMap<String, String>();
        String currentPath = null;
        var current = new StringBuilder();
        for (var line : diff.split("\\R", -1)) {
            if (line.startsWith("diff --git a/")) {
                if (currentPath != null) result.put(currentPath, current.toString());
                current.setLength(0);
                var marker = line.indexOf(" b/");
                currentPath = marker > 0 ? line.substring(marker + 3) : line.substring("diff --git a/".length());
            }
            if (currentPath != null) current.append(line).append('\n');
        }
        if (currentPath != null) result.put(currentPath, current.toString());
        return result;
    }

    static List<ParsedFileDiff> parse(String diff, Map<String, byte[]> afterContents) {
        var result = new ArrayList<ParsedFileDiff>();
        for (var entry : split(diff).entrySet()) {
            var path = entry.getKey();
            var fileDiff = entry.getValue();
            var kind = kindOf(fileDiff);
            var after = kind == ChangeEntry.Kind.DELETED ? new byte[0] : afterContents.get(path);
            var before = kind == ChangeEntry.Kind.ADDED || isBinary(fileDiff) ? null : reconstructBefore(fileDiff, after);
            result.add(new ParsedFileDiff(path, kind, before, fileDiff));
        }
        return result;
    }

    private static ChangeEntry.Kind kindOf(String diff) {
        // 新文件没有旧内容；删除文件没有新内容，其余情况视为普通修改。
        if (diff.contains("\nnew file mode ") || diff.contains("\n--- /dev/null")) return ChangeEntry.Kind.ADDED;
        if (diff.contains("\ndeleted file mode ") || diff.contains("\n+++ /dev/null")) return ChangeEntry.Kind.DELETED;
        return ChangeEntry.Kind.MODIFIED;
    }

    private static boolean isBinary(String diff) {
        return diff.contains("GIT binary patch") || diff.contains("Binary files ");
    }

    private static byte[] reconstructBefore(String diff, byte[] afterContent) {
        if (afterContent == null) return null;

        // 二进制文件不能从 unified diff 安全还原，交给列表显示但禁止撤销。
        var afterText = decode(afterContent);
        if (afterText == null) return null;
        var afterLines = lines(afterText);
        var beforeLines = new ArrayList<String>();
        var afterIndex = 0;
        var hasHunk = false;
        var beforeNoNewline = false;
        var diffLines = diff.split("\\R", -1);
        for (var index = 0; index < diffLines.length; index++) {
            var matcher = HUNK_HEADER.matcher(diffLines[index]);
            if (!matcher.matches()) continue;
            hasHunk = true;
            var newStart = Integer.parseInt(matcher.group(3));
            var targetAfterIndex = Math.max(0, newStart - 1);
            if (targetAfterIndex < afterIndex || targetAfterIndex > afterLines.size()) return null;
            while (afterIndex < targetAfterIndex) beforeLines.add(afterLines.get(afterIndex++));

            String previousLine = null;
            for (index++; index < diffLines.length; index++) {
                var line = diffLines[index];
                if (HUNK_HEADER.matcher(line).matches() || line.startsWith("diff --git a/")) {
                    index--;
                    break;
                }
                if (line.equals("\\ No newline at end of file")) {
                    if (previousLine != null && !previousLine.isEmpty() && previousLine.charAt(0) != '+') beforeNoNewline = true;
                    previousLine = line;
                    continue;
                }
                if (line.isEmpty()) {
                    index--;
                    break;
                }
                var marker = line.charAt(0);
                switch (marker) {
                    case ' ' -> {
                        if (afterIndex >= afterLines.size()) return null;
                        beforeLines.add(line.substring(1));
                        afterIndex++;
                    }
                    case '-' -> beforeLines.add(line.substring(1));
                    case '+' -> {
                        if (afterIndex >= afterLines.size()) return null;
                        afterIndex++;
                    }
                    default -> {
                        return null;
                    }
                }
                previousLine = line;
            }
        }
        if (!hasHunk || afterIndex > afterLines.size()) return null;
        while (afterIndex < afterLines.size()) beforeLines.add(afterLines.get(afterIndex++));
        if (beforeLines.isEmpty()) return new byte[0];

        var beforeText = String.join("\n", beforeLines);
        if (!beforeNoNewline) beforeText += "\n";
        return beforeText.getBytes(StandardCharsets.UTF_8);
    }

    private static String decode(byte[] content) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(content));
            return chars.toString();
        } catch (CharacterCodingException ignored) {
            return null;
        }
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        var result = new ArrayList<>(List.of(text.split("\\n", -1)));
        if (text.endsWith("\n")) result.remove(result.size() - 1);
        return result;
    }

    record ParsedFileDiff(String path, ChangeEntry.Kind kind, byte[] beforeContent, String unifiedDiff) {}
}
