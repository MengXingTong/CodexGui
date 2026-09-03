package com.codexgui.service;

import com.codexgui.model.ChangeEntry;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class UnifiedDiffBuilder {
    private UnifiedDiffBuilder() {}

    static String create(
        String relativePath,
        ChangeEntry.Kind kind,
        byte[] beforeContent,
        byte[] afterContent,
        byte[] beforeHash,
        byte[] afterHash
    ) {
        var header = new StringBuilder("diff --git a/").append(relativePath).append(" b/").append(relativePath).append('\n');
        if (kind == ChangeEntry.Kind.ADDED) header.append("new file mode 100644\n");
        if (kind == ChangeEntry.Kind.DELETED) header.append("deleted file mode 100644\n");

        var beforeText = beforeContent == null ? null : decode(beforeContent);
        var afterText = afterContent == null ? null : decode(afterContent);
        var textDiffAvailable = kind == ChangeEntry.Kind.ADDED ? afterText != null
            : kind == ChangeEntry.Kind.DELETED ? beforeText != null
            : beforeText != null && afterText != null;
        if (!textDiffAvailable) {
            return header.append("Binary files a/").append(relativePath).append(" and b/").append(relativePath)
                .append(" differ (").append(shortHash(beforeHash)).append(" -> ").append(shortHash(afterHash)).append(")\n")
                .toString();
        }

        header.append(kind == ChangeEntry.Kind.ADDED ? "--- /dev/null\n" : "--- a/" + relativePath + "\n");
        header.append(kind == ChangeEntry.Kind.DELETED ? "+++ /dev/null\n" : "+++ b/" + relativePath + "\n");
        appendTextHunk(header, beforeText == null ? "" : beforeText, afterText == null ? "" : afterText);
        return header.toString();
    }

    private static void appendTextHunk(StringBuilder diff, String beforeText, String afterText) {
        var before = textLines(beforeText);
        var after = textLines(afterText);
        var prefix = 0;
        while (prefix < before.lines().size() && prefix < after.lines().size()
            && before.lines().get(prefix).equals(after.lines().get(prefix))) prefix++;
        var suffix = 0;
        while (suffix < before.lines().size() - prefix && suffix < after.lines().size() - prefix
            && before.lines().get(before.lines().size() - suffix - 1).equals(after.lines().get(after.lines().size() - suffix - 1))) suffix++;

        // 仅换行符状态发生变化时，把最后一行纳入差异块。
        if (prefix == before.lines().size() && prefix == after.lines().size() && before.endsWithNewline() != after.endsWithNewline()
            && prefix > 0) {
            prefix--;
            suffix = 0;
        }
        var beforeEnd = before.lines().size() - suffix;
        var afterEnd = after.lines().size() - suffix;
        var beforeCount = beforeEnd - prefix;
        var afterCount = afterEnd - prefix;
        var beforeStart = beforeCount == 0 ? prefix : prefix + 1;
        var afterStart = afterCount == 0 ? prefix : prefix + 1;
        diff.append("@@ -").append(beforeStart).append(',').append(beforeCount)
            .append(" +").append(afterStart).append(',').append(afterCount).append(" @@\n");
        for (var index = prefix; index < beforeEnd; index++) {
            appendDiffLine(diff, '-', before.lines().get(index), before.endsWithNewline(), index, before.lines().size());
        }
        for (var index = prefix; index < afterEnd; index++) {
            appendDiffLine(diff, '+', after.lines().get(index), after.endsWithNewline(), index, after.lines().size());
        }
    }

    private static void appendDiffLine(StringBuilder diff, char marker, String line, boolean endsWithNewline, int index, int lineCount) {
        diff.append(marker).append(line).append('\n');
        if (index == lineCount - 1 && !endsWithNewline) diff.append("\\ No newline at end of file\n");
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

    private static TextLines textLines(String text) {
        var normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isEmpty()) return new TextLines(List.of(), false);
        var endsWithNewline = normalized.endsWith("\n");
        var lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (endsWithNewline) lines.remove(lines.size() - 1);
        return new TextLines(List.copyOf(lines), endsWithNewline);
    }

    private static String shortHash(byte[] hash) {
        if (hash == null) return "none";
        var result = new StringBuilder();
        for (var index = 0; index < Math.min(6, hash.length); index++) result.append(String.format("%02x", hash[index]));
        return result.toString();
    }

    private record TextLines(List<String> lines, boolean endsWithNewline) {}
}
