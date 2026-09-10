import {expect, test, type Locator, type Page} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};
declare const codexHost: (payload: string) => void;

const marker = '\ufffc';

async function openEditor(page: Page, references = true): Promise<Locator> {
  await page.goto(`/tools/ui-preview.html${references ? '?references' : ''}`);
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await expect(editor.locator('[data-file-reference]')).toHaveCount(references ? 3 : 0);
  return editor;
}

async function serializeEditor(editor: Locator): Promise<string> {
  return editor.evaluate((element, referenceMarker) => {
    const serialize = (node: Node): string => {
      if (node.nodeType === Node.TEXT_NODE) return node.nodeValue || '';
      if (!(node instanceof HTMLElement)) return '';
      if (node.hasAttribute('data-file-reference')) return referenceMarker;
      if (node.tagName === 'BR') return node.classList.contains('ProseMirror-trailingBreak') ? '' : '\n';
      return Array.from(node.childNodes).map(serialize).join('');
    };
    return Array.from(element.childNodes).map(serialize).join('');
  }, marker);
}

async function selectionSignature(editor: Locator): Promise<string> {
  return editor.evaluate(element => `${element.dataset.selectionAnchor}:${element.dataset.selectionHead}`);
}

async function pressAndWaitForSelection(editor: Locator, key: string): Promise<string> {
  const previous = await selectionSignature(editor);
  await editor.press(key);
  await expect.poll(() => selectionSignature(editor)).not.toBe(previous);
  return selectionSignature(editor);
}

async function seedNavigationDocument(editor: Locator): Promise<void> {
  await editor.locator('[data-file-reference]').first().click();
  await editor.press('ArrowLeft');
  await expect.poll(() => selectionSignature(editor)).toBe('0:0');
  await editor.pressSequentially('左侧');

  await editor.locator('[data-file-reference]').last().click();
  const selectedReference = await selectionSignature(editor);
  await editor.press('ArrowRight');
  await expect.poll(() => selectionSignature(editor)).not.toBe(selectedReference);
  await editor.pressSequentially('右侧');
  expect(await serializeEditor(editor)).toBe(`左侧${marker}${marker}${marker}右侧`);
}

test('空输入框提示不参与光标排版', async ({page}) => {
  const editor = await openEditor(page, false);
  await editor.click({position: {x: 2, y: 9}});

  await expect.poll(() => selectionSignature(editor)).toBe('0:0');
  const layout = await editor.evaluate(element => ({
    placeholderPosition: getComputedStyle(element, '::before').position,
    text: element.textContent,
  }));
  expect(layout).toEqual({placeholderPosition: 'absolute', text: ''});
});

test('文件标签不改变文字光标高度并为两侧光标保留间隔', async ({page}) => {
  const editor = await openEditor(page);
  const lastReference = editor.locator('[data-file-reference]').last();
  await lastReference.click();
  await editor.press('ArrowRight');
  await expect.poll(() => selectionSignature(editor)).toBe('3:3');

  const layout = await lastReference.evaluate(element => {
    const chip = element.querySelector('.file-reference-chip');
    if (!(chip instanceof HTMLElement)) throw new Error('文件标签缺少可视节点');
    const atomRect = element.getBoundingClientRect();
    const chipRect = chip.getBoundingClientRect();
    const editor = element.closest('.input');
    if (!(editor instanceof HTMLElement)) throw new Error('文件标签缺少输入框');
    const editorStyle = getComputedStyle(editor);
    return {
      atomLeft: atomRect.left,
      atomRight: atomRect.right,
      atomHeight: atomRect.height,
      chipLeft: chipRect.left,
      chipRight: chipRect.right,
      atomEditable: element.getAttribute('contenteditable'),
      editorFontSize: editorStyle.fontSize,
      editorLineHeight: Number.parseFloat(editorStyle.lineHeight),
    };
  });
  expect(layout.chipLeft - layout.atomLeft).toBeGreaterThanOrEqual(2);
  expect(layout.atomRight - layout.chipRight).toBeGreaterThanOrEqual(2);
  expect(Math.abs(layout.atomHeight - layout.editorLineHeight)).toBeLessThanOrEqual(1);
  expect(layout.editorFontSize).toBe('13px');
  expect(layout.atomEditable).toBe('false');
});

test('预览页可切换回默认身份', async ({page}) => {
  await openEditor(page, false);
  const selector = page.getByTitle('切换 Agent 身份');
  await expect(selector).toContainText('代码审查员');
  await selector.click();
  await page.locator('[data-agent-select=""]').click();

  await expect(page.getByTitle('切换 Agent 身份')).toContainText('默认身份');
  const sent = await page.evaluate(() => (window as Window & {
    previewLastMessage?: {type: string; payload: {id: string}};
  }).previewLastMessage);
  expect(sent).toMatchObject({type: 'selectAgent', payload: {id: ''}});
});

test('捕获列表悬浮文件名时在上方显示相对路径并可打开所在文件夹', async ({page}) => {
  await openEditor(page, false);
  await page.getByRole('button', {name: '编辑'}).click();

  const change = page.locator('[data-change-row="0"]');
  const fileName = change.locator('.change-name');
  await fileName.hover();
  const tooltip = page.locator('#change-path-tooltip');
  await expect(tooltip).toBeVisible();
  await expect(tooltip).toHaveText('src/main/java/com/codexgui/ui/CodexToolWindowPanel.java');
  const positions = await Promise.all([fileName.boundingBox(), tooltip.boundingBox()]);
  expect(positions[0]).not.toBeNull();
  expect(positions[1]).not.toBeNull();
  expect(positions[1]!.y + positions[1]!.height).toBeLessThan(positions[0]!.y);
  expect(Math.abs(positions[1]!.x - positions[0]!.x)).toBeLessThanOrEqual(1);

  await change.click({button: 'right'});
  await page.getByRole('button', {name: '在文件夹中打开'}).click();

  const sent = await page.evaluate(() => (window as Window & {
    previewLastMessage?: {type: string; payload: {index: number}};
  }).previewLastMessage);
  expect(sent).toMatchObject({type: 'openChangeLocation', payload: {index: 0}});
});

test('连续文件标签两侧的左右方向键始终推进一个逻辑位置', async ({page}) => {
  const editor = await openEditor(page);
  await seedNavigationDocument(editor);

  await pressAndWaitForSelection(editor, 'Home');
  const rightPositions = [await selectionSignature(editor)];
  for (let index = 0; index < 7; index++) {
    rightPositions.push(await pressAndWaitForSelection(editor, 'ArrowRight'));
  }
  expect(new Set(rightPositions).size).toBe(8);

  const leftPositions = [await selectionSignature(editor)];
  for (let index = 0; index < 7; index++) {
    leftPositions.push(await pressAndWaitForSelection(editor, 'ArrowLeft'));
  }
  expect(new Set(leftPositions).size).toBe(8);

  // 标签按原子位置跨越，跨过首个标签后的输入必须出现在首、次标签之间。
  await pressAndWaitForSelection(editor, 'ArrowRight');
  await pressAndWaitForSelection(editor, 'ArrowRight');
  await pressAndWaitForSelection(editor, 'ArrowRight');
  await editor.pressSequentially('中');
  expect(await serializeEditor(editor)).toBe(`左侧${marker}中${marker}${marker}右侧`);
});

test('选择、相邻删除、全选替换和撤销重做保持引用状态一致', async ({page}) => {
  let editor = await openEditor(page);

  await seedNavigationDocument(editor);
  await page.waitForTimeout(550);
  await pressAndWaitForSelection(editor, 'Home');
  await pressAndWaitForSelection(editor, 'ArrowRight');
  await pressAndWaitForSelection(editor, 'ArrowRight');
  await pressAndWaitForSelection(editor, 'Shift+ArrowRight');
  await editor.pressSequentially('替');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(2);
  expect(await serializeEditor(editor)).toBe(`左侧替${marker}${marker}右侧`);

  await editor.press('Control+z');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(3);
  expect(await serializeEditor(editor)).toBe(`左侧${marker}${marker}${marker}右侧`);
  await editor.press('Control+y');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(2);

  editor = await openEditor(page);
  await seedNavigationDocument(editor);
  await pressAndWaitForSelection(editor, 'Home');
  await pressAndWaitForSelection(editor, 'ArrowRight');
  await pressAndWaitForSelection(editor, 'ArrowRight');
  await editor.press('Delete');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(2);
  await pressAndWaitForSelection(editor, 'End');
  await pressAndWaitForSelection(editor, 'ArrowLeft');
  await pressAndWaitForSelection(editor, 'ArrowLeft');
  await editor.press('Backspace');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(1);
  await editor.press('Control+a');
  await editor.pressSequentially('全部替换');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(0);
  expect(await serializeEditor(editor)).toBe('全部替换');
});

test('文件补全与混合剪贴板使用异步书签恢复到原位置', async ({page}) => {
  let editor = await openEditor(page, false);
  await editor.pressSequentially('前 @app');
  const completion = page.getByRole('option').filter({hasText: 'app.js'});
  await expect(completion).toBeVisible();
  await completion.click();
  await editor.pressSequentially('后');
  await page.getByRole('button', {name: '新建页签'}).click();
  await page.locator('[data-session-tab]').first().click();
  editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor.locator('[data-file-reference]')).toHaveCount(1);
  expect(await serializeEditor(editor)).toBe(`前 ${marker} 后`);

  editor = await openEditor(page, false);
  await editor.evaluate((element, payload) => {
    const clipboard = new DataTransfer();
    clipboard.setData('application/x-codex-file-references', JSON.stringify(payload));
    element.dispatchEvent(new ClipboardEvent('paste', {bubbles: true, cancelable: true, clipboardData: clipboard}));
  }, {
    text: `左${marker}中${marker}右`,
    referenceIds: [],
    paths: ['D:\\Project\\One.cpp', 'D:\\Project\\Two.cpp'],
    plain: '左@D:\\Project\\One.cpp 中@D:\\Project\\Two.cpp 右',
  });
  await editor.pressSequentially('后续');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(2);
  expect(await serializeEditor(editor)).toBe(`左${marker}中${marker}右后续`);

  await editor.press('Control+a');
  await editor.press('Control+c');
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain('@D:\\Project\\One.cpp');
  await editor.press('Control+x');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(0);
  await editor.press('Control+v');
  await expect(editor.locator('[data-file-reference]')).toHaveCount(2);
  expect(await serializeEditor(editor)).toBe(`左${marker}中${marker}右后续`);
});

test('中文组合、后台更新和页签重绘不销毁当前编辑状态', async ({page}) => {
  let editor = await openEditor(page, false);
  await editor.pressSequentially('组合前');
  await editor.evaluate(element => {
    element.dataset.editorInstance = 'original';
    element.dispatchEvent(new CompositionEvent('compositionstart', {bubbles: true, data: ''}));
  });
  await page.evaluate(() => {
    const envelope = previewEnvelope('toast', {message: '后台更新'});
    CodexGui.receive(envelope);
  });
  await editor.pressSequentially('中文');
  await editor.evaluate(element => {
    element.dispatchEvent(new CompositionEvent('compositionend', {bubbles: true, data: '中文'}));
  });
  await expect.poll(() => editor.getAttribute('data-editor-instance')).toBe('original');
  expect(await serializeEditor(editor)).toBe('组合前中文');

  await page.getByRole('button', {name: '新建页签'}).click();
  editor = page.getByRole('textbox', {name: '消息'});
  await editor.pressSequentially('第二页签');
  await page.locator('[data-session-tab]').first().click();
  editor = page.getByRole('textbox', {name: '消息'});
  expect(await serializeEditor(editor)).toBe('组合前中文');
  await page.locator('[data-session-tab]').nth(1).click();
  editor = page.getByRole('textbox', {name: '消息'});
  expect(await serializeEditor(editor)).toBe('第二页签');
});

test('换行、滚动、内部拖动和外部落点保持结构化文档', async ({page}) => {
  let editor = await openEditor(page, false);
  for (let index = 0; index < 24; index++) {
    await editor.pressSequentially(`第${index + 1}行`);
    if (index < 23) await editor.press('Shift+Enter');
  }
  expect((await serializeEditor(editor)).split('\n')).toHaveLength(24);
  const scroll = await editor.evaluate(element => ({
    clientHeight: element.clientHeight,
    scrollHeight: element.scrollHeight,
    scrollTop: element.scrollTop,
  }));
  expect(scroll.clientHeight).toBeLessThanOrEqual(240);
  expect(scroll.scrollHeight).toBeGreaterThan(scroll.clientHeight);
  expect(scroll.scrollTop).toBeGreaterThan(0);

  editor = await openEditor(page);
  const chips = editor.locator('[data-file-reference]');
  await chips.first().dragTo(chips.last());
  await expect.poll(async () => editor.locator('[data-file-reference]').evaluateAll(
    elements => elements.map(element => element.getAttribute('data-file-reference')),
  )).not.toEqual(['ref-1', 'ref-2', 'ref-3']);

  const box = await editor.boundingBox();
  const firstChipBox = await chips.first().boundingBox();
  if (!box || !firstChipBox) throw new Error('输入框没有可用布局');
  await page.evaluate(({x, y}) => {
    CodexGui.receive(previewEnvelope('nativeDrop', {x: x / innerWidth, y: y / innerHeight}));
    codexHost(JSON.stringify({
      v: 1,
      type: 'dropFiles',
      requestId: 'drop-test',
      sessionId: 'default',
      turnId: '',
      generation: 0,
      payload: {},
    }));
  }, {x: Math.max(box.x + 1, firstChipBox.x - 2), y: firstChipBox.y + firstChipBox.height / 2});
  await expect(editor.locator('[data-file-reference]')).toHaveCount(4);
  expect(await editor.locator('[data-file-reference]').first().getAttribute('data-file-reference')).toBe('ref-4');

  const referenceIds = await editor.locator('[data-file-reference]').evaluateAll(
    elements => elements.map(element => element.getAttribute('data-file-reference')),
  );
  const text = await serializeEditor(editor);
  await page.getByRole('button', {name: '发送'}).click();
  const sent = await page.evaluate(() => (window as Window & {
    previewLastMessage?: {type: string; payload: {text: string; referenceIds: string[]}};
  }).previewLastMessage);
  expect(sent).toMatchObject({type: 'send', payload: {text, referenceIds}});
});

test('自定义模型固定显示在供应商模型前并可直接添加', async ({page}) => {
  await openEditor(page, false);
  await page.getByTitle('模型').click();

  const labels = page.locator('.model-selector-menu .model-group-label');
  await expect(labels).toHaveText(['自定义模型', '供应商模型']);
  await expect(page.locator('.model-custom-row').first()).toContainText('company/custom-model');

  await page.getByRole('button', {name: '添加自定义模型'}).click();
  await expect(page.locator('[data-menu="model"]')).toContainText('preview/custom-model');
});

test('Codex 输入栏可选择完全访问沙箱', async ({page}) => {
  await openEditor(page, false);
  await page.getByTitle('文件沙箱：工作区').click();
  await page.locator('[data-choice="sandbox"][data-value="danger-full-access"]').click();

  await expect(page.getByTitle('文件沙箱：完全访问')).toBeVisible();
  const sent = await page.evaluate(() => (window as Window & {
    previewLastMessage?: {type: string; payload: {key: string; value: string}};
  }).previewLastMessage);
  expect(sent).toMatchObject({
    type: 'setting',
    payload: {key: 'sandbox', value: 'danger-full-access'},
  });
});

test('其它页签运行时仍可在空闲页签切换 Claude 渠道', async ({page}) => {
  await openEditor(page, false);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('busy', {busy: true, queuedCount: 0})));
  await expect(page.getByTitle('停止')).toBeVisible();

  await page.getByRole('button', {name: '新建页签'}).click();
  await page.getByTitle('聊天设置').first().click();
  await page.getByRole('button', {name: /切换当前渠道/}).click();
  await page.locator('[data-provider-select="claude"]').click();

  const sent = await page.evaluate(() => (window as Window & {
    previewLastMessage?: {type: string; sessionId: string; payload: {provider: string}};
  }).previewLastMessage);
  expect(sent).toMatchObject({type: 'new', payload: {provider: 'claude'}});
  expect(sent?.sessionId).not.toBe('default');
});
