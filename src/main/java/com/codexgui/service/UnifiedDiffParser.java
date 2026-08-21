package com.codexgui.service;

import java.util.LinkedHashMap;
import java.util.Map;

final class UnifiedDiffParser {
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
}
