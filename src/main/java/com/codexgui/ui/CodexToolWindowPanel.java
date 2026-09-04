package com.codexgui.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.codexgui.service.Utf8IO;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.InputStream;

final class CodexToolWindowPanel extends JPanel implements Disposable {
    private final JBCefBrowser browser;
    private final JBCefJSQuery bridge;
    private final CodexToolWindowController controller;

    CodexToolWindowPanel(Project project) {
        super(new BorderLayout());
        if (!JBCefApp.isSupported()) {
            browser = null;
            bridge = null;
            controller = null;
            add(new JLabel("当前 JetBrains Runtime 不支持 JCEF，无法显示 CodeDeck。", SwingConstants.CENTER));
            return;
        }

        // 面板只负责创建 JCEF、组装控制器并加载静态页面。
        browser = new JBCefBrowser();
        bridge = JBCefJSQuery.create((JBCefBrowserBase) browser);
        controller = new CodexToolWindowController(project, this, browser);
        bridge.addHandler(payload -> {
            controller.handleBridgeMessage(payload);
            return new JBCefJSQuery.Response(null);
        });
        add(browser.getComponent(), BorderLayout.CENTER);
        browser.setPageBackgroundColor("#1e1e1e");
        browser.loadHTML(buildWebApp(), "http://codex-gui.local/");
    }

    private String buildWebApp() {
        var template = resource("/web/index.html");
        var bridgeScript = "window.codexHost = function(payload) {" + bridge.inject("payload") + ";};";
        return template
            .replace("/*__CODEX_GUI_STYLE__*/", resource("/web/app.css"))
            .replace("/*__CODEX_GUI_BRIDGE__*/", bridgeScript)
            .replace("/*__CODEX_GUI_SCRIPT__*/", resource("/web/app.js"));
    }

    private String resource(String name) {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("缺少界面资源：" + name);
            return Utf8IO.read(input);
        } catch (IOException error) {
            throw new IllegalStateException("无法读取界面资源：" + name, error);
        }
    }

    @Override
    public void dispose() {
        if (controller != null) controller.dispose();
        if (bridge != null) bridge.dispose();
        if (browser != null) browser.dispose();
    }
}
