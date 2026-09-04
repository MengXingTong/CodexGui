package com.codexgui.service;

import com.intellij.openapi.util.SystemInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ExecutableCommand {
    private ExecutableCommand() {}

    public static List<String> build(String executable, List<String> arguments) {
        var command = new ArrayList<String>();
        // Windows 的 npm shim 和无扩展名命令需要由 cmd 解析；原生 exe 可直接启动。
        if (SystemInfo.isWindows && !executable.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            command.add("cmd.exe");
            command.add("/d");
            command.add("/c");
        }
        command.add(executable);
        command.addAll(arguments);
        return command;
    }
}
