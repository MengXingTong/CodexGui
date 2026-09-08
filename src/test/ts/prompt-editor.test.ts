/** @vitest-environment jsdom */

import {afterEach, describe, expect, it, vi} from 'vitest';
import {
  PROMPT_REFERENCE_MARKER,
  PromptEditor,
  type PromptEditorOptions,
  type PromptFileReference,
  type PromptSnapshot,
} from '../../main/ts/prompt-editor';

const firstReference: PromptFileReference = {
  id: 'ref-1',
  name: 'First.cpp',
  path: 'D:\\Project\\First.cpp',
  directory: false,
};
const secondReference: PromptFileReference = {
  id: 'ref-2',
  name: 'Second.cpp',
  path: 'D:\\Project\\Second.cpp',
  directory: false,
};

const editors: PromptEditor[] = [];

function createEditor(snapshot: PromptSnapshot, references: PromptFileReference[] = []) {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const callbacks = {
    onChange: vi.fn<PromptEditorOptions['onChange']>(),
    onAddReferences: vi.fn<PromptEditorOptions['onAddReferences']>(),
    onReferencesChange: vi.fn<PromptEditorOptions['onReferencesChange']>(),
  };
  const editor = new PromptEditor({
    host,
    snapshot,
    references,
    placeholder: '输入消息',
    shouldSubmit: () => true,
    onSubmit: vi.fn(),
    ...callbacks,
    onContextMenu: vi.fn(),
  });
  editors.push(editor);
  return {editor, callbacks};
}

afterEach(() => {
  editors.splice(0).forEach(editor => editor.destroy());
  document.body.replaceChildren();
});

describe('PromptEditor 文档模型', () => {
  it('往返恢复文本、换行、引用和双向选择', () => {
    const snapshot: PromptSnapshot = {
      segments: [
        {type: 'text', text: '左侧\n'},
        {type: 'reference', referenceId: firstReference.id, referencePath: firstReference.path},
        {type: 'text', text: '右侧'},
      ],
      anchor: 5,
      head: 2,
    };
    const {editor} = createEditor(snapshot, [firstReference]);

    expect(editor.snapshot()).toEqual(snapshot);
    expect(editor.serializeForSend()).toEqual({
      text: `左侧\n${PROMPT_REFERENCE_MARKER}右侧`,
      referenceIds: ['ref-1'],
    });
  });

  it('相同路径的不同 ID 保持文档顺序并逐一序列化', () => {
    const duplicate = {...secondReference, path: firstReference.path};
    const {editor} = createEditor({
      segments: [
        {type: 'reference', referenceId: duplicate.id, referencePath: duplicate.path},
        {type: 'text', text: '中间'},
        {type: 'reference', referenceId: firstReference.id, referencePath: firstReference.path},
      ],
      anchor: 4,
      head: 4,
    }, [firstReference, duplicate]);

    expect(editor.serializeForSend()).toEqual({
      text: `${PROMPT_REFERENCE_MARKER}中间${PROMPT_REFERENCE_MARKER}`,
      referenceIds: ['ref-2', 'ref-1'],
    });
  });
});

describe('PromptEditor 引用事务', () => {
  it('异步引用书签会跨后续输入映射并插入原位置', () => {
    const {editor, callbacks} = createEditor({segments: [{type: 'text', text: 'ab'}], anchor: 1, head: 1});

    editor.requestReferences([firstReference.path]);
    editor.replaceLinearRange(1, 1, 'X');
    editor.reconcileReferences([firstReference]);

    expect(callbacks.onAddReferences).toHaveBeenCalledWith([firstReference.path]);
    expect(editor.serializeForSend()).toEqual({
      text: `a${PROMPT_REFERENCE_MARKER}Xb`,
      referenceIds: ['ref-1'],
    });
    expect(editor.snapshot()).toMatchObject({anchor: 3, head: 3});
  });

  it('异步书签会随草稿快照跨编辑器实例恢复', () => {
    const first = createEditor({segments: [{type: 'text', text: 'ab'}], anchor: 1, head: 1});
    first.editor.requestReferences([firstReference.path]);
    first.editor.replaceLinearRange(1, 1, 'X');
    const snapshot = first.editor.snapshot();

    expect(snapshot.pendingReferences).toEqual([{
      position: 1,
      paths: [firstReference.path],
      value: PROMPT_REFERENCE_MARKER,
    }]);
    const restored = createEditor(snapshot, [firstReference]);
    expect(restored.editor.serializeForSend()).toEqual({
      text: `a${PROMPT_REFERENCE_MARKER}Xb`,
      referenceIds: ['ref-1'],
    });
    expect(restored.editor.snapshot().pendingReferences).toBeUndefined();
  });

  it('同路径的并发异步请求按各自书签消费稳定 ID', () => {
    const duplicate = {...secondReference, path: firstReference.path};
    const {editor} = createEditor({segments: [{type: 'text', text: 'ab'}], anchor: 1, head: 1});

    editor.requestReferences([firstReference.path], PROMPT_REFERENCE_MARKER, 1, 1);
    editor.requestReferences([firstReference.path], PROMPT_REFERENCE_MARKER, 2, 2);
    editor.reconcileReferences([firstReference, duplicate]);

    expect(editor.serializeForSend()).toEqual({
      text: `a${PROMPT_REFERENCE_MARKER}b${PROMPT_REFERENCE_MARKER}`,
      referenceIds: ['ref-1', 'ref-2'],
    });
  });

  it('本地删除只通知一次，后端对账不会重复发送删除', () => {
    const {editor, callbacks} = createEditor({
      segments: [
        {type: 'reference', referenceId: firstReference.id},
        {type: 'reference', referenceId: secondReference.id},
      ],
      anchor: 2,
      head: 2,
    }, [firstReference, secondReference]);

    expect(editor.removeReference(firstReference.id)).toBe(true);
    expect(callbacks.onReferencesChange).toHaveBeenCalledTimes(1);
    expect(callbacks.onReferencesChange).toHaveBeenCalledWith([secondReference]);

    editor.reconcileReferences([secondReference]);
    expect(callbacks.onReferencesChange).toHaveBeenCalledTimes(1);
    expect(editor.serializeForSend().referenceIds).toEqual(['ref-2']);
  });

  it('后端删除和清空编辑器都不会回发删除请求', () => {
    const {editor, callbacks} = createEditor({
      segments: [
        {type: 'reference', referenceId: firstReference.id},
        {type: 'text', text: '正文'},
      ],
      anchor: 3,
      head: 3,
    }, [firstReference]);

    editor.reconcileReferences([]);
    editor.clear();

    expect(callbacks.onReferencesChange).not.toHaveBeenCalled();
    expect(editor.serializeForSend()).toEqual({text: '', referenceIds: []});
    expect(editor.element.dataset.empty).toBe('true');
  });
});
