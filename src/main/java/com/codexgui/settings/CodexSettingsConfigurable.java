package com.codexgui.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Objects;

public final class CodexSettingsConfigurable implements Configurable {
    private JBTextField executableField;
    private JBTextField modelField;
    private ComboBox<Choice> effortCombo;
    private ComboBox<Choice> serviceTierCombo;
    private ComboBox<Choice> approvalCombo;
    private ComboBox<Choice> sandboxCombo;
    private JBCheckBox streamingCheckBox;
    private JBCheckBox captureIgnoredCheckBox;

    @Override
    public @Nls String getDisplayName() {
        return "Codex GUI";
    }

    @Override
    public @Nullable JComponent createComponent() {
        var state = CodexSettingsState.getInstance().getState();
        executableField = new JBTextField(state.codexExecutable);
        modelField = new JBTextField(state.model);
        effortCombo = new ComboBox<>(new Choice[]{new Choice("minimal", "最少"), new Choice("low", "低"), new Choice("medium", "中"), new Choice("high", "高"), new Choice("xhigh", "极高"), new Choice("ultra", "最高")});
        select(effortCombo, state.reasoningEffort);
        serviceTierCombo = new ComboBox<>(new Choice[]{new Choice("standard", "标准"), new Choice("fast", "Fast")});
        select(serviceTierCombo, state.serviceTier);
        approvalCombo = new ComboBox<>(new Choice[]{new Choice("untrusted", "谨慎审批"), new Choice("on-request", "按需审批"), new Choice("never", "全自动")});
        select(approvalCombo, state.approvalPolicy);
        sandboxCombo = new ComboBox<>(new Choice[]{new Choice("read-only", "只读"), new Choice("workspace-write", "标准"), new Choice("danger-full-access", "完全访问")});
        select(sandboxCombo, state.sandboxMode);
        streamingCheckBox = new JBCheckBox("流式显示 Codex 输出", state.streamResponses);
        captureIgnoredCheckBox = new JBCheckBox("捕获 Git 忽略文件的修改", state.captureIgnoredFiles);

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(new JBLabel("Codex 可执行文件："), executableField)
            .addTooltip("可以填写 codex、codex.cmd 或 Codex CLI 的绝对路径")
            .addLabeledComponent(new JBLabel("默认模型（留空使用 Codex 配置）："), modelField)
            .addLabeledComponent(new JBLabel("默认推理强度："), effortCombo)
            .addLabeledComponent(new JBLabel("响应模式："), serviceTierCombo)
            .addLabeledComponent(new JBLabel("默认审批策略："), approvalCombo)
            .addLabeledComponent(new JBLabel("默认沙箱："), sandboxCombo)
            .addComponent(streamingCheckBox)
            .addComponent(captureIgnoredCheckBox)
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
    }

    @Override
    public boolean isModified() {
        var state = CodexSettingsState.getInstance().getState();
        return !Objects.equals(state.codexExecutable, executableField.getText().trim())
            || !Objects.equals(state.model, modelField.getText().trim())
            || !Objects.equals(state.reasoningEffort, value(effortCombo, "high"))
            || !Objects.equals(state.serviceTier, value(serviceTierCombo, "standard"))
            || !Objects.equals(state.approvalPolicy, value(approvalCombo, "on-request"))
            || !Objects.equals(state.sandboxMode, value(sandboxCombo, "workspace-write"))
            || state.streamResponses != streamingCheckBox.isSelected()
            || state.captureIgnoredFiles != captureIgnoredCheckBox.isSelected();
    }

    @Override
    public void apply() {
        var state = CodexSettingsState.getInstance().getState();
        state.codexExecutable = executableField.getText().trim();
        state.model = modelField.getText().trim();
        state.reasoningEffort = value(effortCombo, "high");
        state.serviceTier = value(serviceTierCombo, "standard");
        state.approvalPolicy = value(approvalCombo, "on-request");
        state.sandboxMode = value(sandboxCombo, "workspace-write");
        state.streamResponses = streamingCheckBox.isSelected();
        state.captureIgnoredFiles = captureIgnoredCheckBox.isSelected();
    }

    @Override
    public void reset() {
        var state = CodexSettingsState.getInstance().getState();
        executableField.setText(state.codexExecutable);
        modelField.setText(state.model);
        select(effortCombo, state.reasoningEffort);
        select(serviceTierCombo, state.serviceTier);
        select(approvalCombo, state.approvalPolicy);
        select(sandboxCombo, state.sandboxMode);
        streamingCheckBox.setSelected(state.streamResponses);
        captureIgnoredCheckBox.setSelected(state.captureIgnoredFiles);
    }

    private void select(ComboBox<Choice> comboBox, String value) {
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            if (Objects.equals(comboBox.getItemAt(index).value(), value)) comboBox.setSelectedIndex(index);
        }
    }

    private String value(ComboBox<Choice> comboBox, String fallback) {
        return comboBox.getSelectedItem() instanceof Choice choice ? choice.value() : fallback;
    }

    private record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}
