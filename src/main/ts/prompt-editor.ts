import {baseKeymap} from 'prosemirror-commands';
import {dropCursor} from 'prosemirror-dropcursor';
import {history, redo, undo} from 'prosemirror-history';
import {keymap} from 'prosemirror-keymap';
import {Fragment, Node as ProseMirrorNode, Schema} from 'prosemirror-model';
import {EditorState, NodeSelection, Plugin, PluginKey, TextSelection, Transaction} from 'prosemirror-state';
import {EditorView, NodeView} from 'prosemirror-view';

export const PROMPT_REFERENCE_MARKER = '\ufffc';

export interface PromptFileReference {
  id: string;
  name: string;
  path: string;
  directory?: boolean;
}

export type PromptSegment =
  | {type: 'text'; text: string}
  | {type: 'reference'; referenceId: string; referencePath?: string};

export interface PromptSnapshot {
  segments: PromptSegment[];
  anchor: number;
  head: number;
  pendingReferences?: PromptPendingReference[];
  externalDropPosition?: number;
}

export interface PromptPendingReference {
  position: number;
  paths: string[];
  value: string;
}

export interface PromptPayload {
  text: string;
  referenceIds: string[];
}

export interface PromptClipboardPayload extends PromptPayload {
  paths: string[];
  plain: string;
}

export interface PromptLinearState {
  value: string;
  anchor: number;
  head: number;
}

export interface PromptEditorOptions {
  host: HTMLElement;
  snapshot?: PromptSnapshot | Array<{text?: string; referenceId?: string; referencePath?: string}>;
  references: PromptFileReference[];
  placeholder: string;
  shouldSubmit: (event: KeyboardEvent) => boolean;
  onSubmit: () => void;
  onChange: (snapshot: PromptSnapshot, payload: PromptPayload, references: PromptFileReference[]) => void;
  onAddReferences: (paths: string[]) => void;
  onReferencesChange: (references: PromptFileReference[]) => void;
  onContextMenu: (reference: PromptFileReference, x: number, y: number) => void;
  onKeyDown?: (event: KeyboardEvent) => boolean;
  onUpdate?: () => void;
}

interface PendingReferenceInsertion {
  token: number;
  position: number;
  paths: string[];
  value: string;
}

interface PendingReferenceMeta {
  add?: PendingReferenceInsertion;
  consume?: number[];
  clear?: boolean;
}

const externalSyncKey = 'prompt-editor-external-sync';
const pendingReferenceKey = new PluginKey<PendingReferenceInsertion[]>('prompt-editor-pending-references');

function createPendingReferencePlugin(initial: PendingReferenceInsertion[]): Plugin<PendingReferenceInsertion[]> {
  return new Plugin<PendingReferenceInsertion[]>({
    key: pendingReferenceKey,
    state: {
      init: () => initial,
      apply(transaction, pending) {
        const meta = transaction.getMeta(pendingReferenceKey) as PendingReferenceMeta | undefined;
        if (meta?.clear) return [];

        // 书签固定在同位置新输入的左侧，使异步返回的标签始终落在用户后续输入之前。
        let next = pending.map(item => ({
          ...item,
          position: transaction.mapping.map(item.position, -1),
        }));
        if (meta?.consume?.length) {
          const consumed = new Set(meta.consume);
          next = next.filter(item => !consumed.has(item.token));
        }
        if (meta?.add) next.push(meta.add);
        return next;
      },
    },
  });
}

const promptSchema = new Schema({
  nodes: {
    doc: {content: 'inline*'},
    text: {group: 'inline'},
    hard_break: {
      inline: true,
      group: 'inline',
      selectable: false,
      parseDOM: [{tag: 'br'}],
      toDOM: () => ['br'],
    },
    file_reference: {
      inline: true,
      group: 'inline',
      atom: true,
      selectable: true,
      draggable: true,
      attrs: {
        id: {},
        name: {default: '文件'},
        path: {default: ''},
        directory: {default: false},
      },
      parseDOM: [{
        tag: '[data-file-reference]',
        getAttrs: element => {
          const node = element as HTMLElement;
          return {
            id: node.dataset.fileReference || '',
            name: node.dataset.fileReferenceName || node.textContent || '文件',
            path: node.getAttribute('title') || '',
            directory: node.classList.contains('directory'),
          };
        },
      }],
      toDOM: node => ['span', {
        'data-file-reference': node.attrs.id,
        'data-file-reference-name': node.attrs.name,
        title: node.attrs.path,
        class: 'file-reference-node',
        contenteditable: 'false',
      }, ['span', {
        class: `file-reference-chip${node.attrs.directory ? ' directory' : ''}`,
      }, node.attrs.name]],
    },
  },
});

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, Number.isFinite(value) ? value : minimum));
}

function normalizeSegments(snapshot: PromptEditorOptions['snapshot']): PromptSnapshot {
  if (snapshot && !Array.isArray(snapshot) && Array.isArray(snapshot.segments)) {
    return {
      segments: snapshot.segments,
      anchor: Number(snapshot.anchor) || 0,
      head: Number(snapshot.head) || 0,
      pendingReferences: Array.isArray(snapshot.pendingReferences) ? snapshot.pendingReferences : [],
      externalDropPosition: Number.isFinite(snapshot.externalDropPosition) ? snapshot.externalDropPosition : undefined,
    };
  }

  const legacy = Array.isArray(snapshot) ? snapshot : [];
  const segments: PromptSegment[] = [];
  for (const item of legacy) {
    if (item.referenceId) segments.push({type: 'reference', referenceId: item.referenceId, referencePath: item.referencePath});
    else if (item.text) segments.push({type: 'text', text: item.text});
  }
  const end = segments.reduce((length, segment) => length + (segment.type === 'text' ? segment.text.length : 1), 0);
  return {segments, anchor: end, head: end, pendingReferences: []};
}

function appendTextSegment(segments: PromptSegment[], text: string): void {
  if (!text) return;
  const previous = segments.at(-1);
  if (previous?.type === 'text') previous.text += text;
  else segments.push({type: 'text', text});
}

function referenceFromNode(node: ProseMirrorNode): PromptFileReference {
  return {
    id: String(node.attrs.id || ''),
    name: String(node.attrs.name || '文件'),
    path: String(node.attrs.path || ''),
    directory: Boolean(node.attrs.directory),
  };
}

function fileIcon(directory: boolean): string {
  if (directory) return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h7l2 2h9v11H3z"></path></svg>';
  return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6z"></path><path d="M14 3v5h5"></path></svg>';
}

export class PromptEditor {
  private readonly options: PromptEditorOptions;
  private readonly view: EditorView;
  private references = new Map<string, PromptFileReference>();
  private pendingSequence = 0;
  private externalDropPosition: number | null = null;

  constructor(options: PromptEditorOptions) {
    this.options = options;
    options.references.forEach(reference => this.references.set(reference.id, reference));
    const snapshot = normalizeSegments(options.snapshot);
    const doc = this.docFromSegments(snapshot.segments);
    const anchor = clamp(snapshot.anchor, 0, doc.content.size);
    const head = clamp(snapshot.head, 0, doc.content.size);
    const initialPending = (snapshot.pendingReferences || []).map(pending => ({
      token: ++this.pendingSequence,
      position: clamp(pending.position, 0, doc.content.size),
      paths: [...pending.paths],
      value: pending.value,
    }));
    if (Number.isFinite(snapshot.externalDropPosition)) {
      this.externalDropPosition = clamp(snapshot.externalDropPosition!, 0, doc.content.size);
    }
    const state = EditorState.create({
      schema: promptSchema,
      doc,
      selection: TextSelection.create(doc, anchor, head),
      plugins: [
        history(),
        dropCursor({color: 'var(--accent)', width: 2}),
        keymap({'Mod-z': undo, 'Shift-Mod-z': redo, 'Mod-y': redo}),
        keymap(baseKeymap),
        createPendingReferencePlugin(initialPending),
      ],
    });

    this.view = new EditorView(options.host, {
      state,
      attributes: {
        id: 'prompt',
        class: 'input',
        role: 'textbox',
        'aria-label': '消息',
        'aria-multiline': 'true',
        'data-placeholder': options.placeholder,
        spellcheck: 'true',
      },
      nodeViews: {
        file_reference: (node, view, getPos) => this.createReferenceNodeView(node, view, getPos),
      },
      dispatchTransaction: transaction => this.dispatchTransaction(transaction),
      handleKeyDown: (_view, event) => this.handleKeyDown(event),
      handleDOMEvents: {
        copy: (_view, event) => this.handleCopy(event as ClipboardEvent, false),
        cut: (_view, event) => this.handleCopy(event as ClipboardEvent, true),
        paste: (_view, event) => this.handlePaste(event as ClipboardEvent),
        compositionstart: () => false,
        compositionend: () => {
          queueMicrotask(() => this.options.onUpdate?.());
          return false;
        },
      },
    });
    this.syncDomState();
    this.reconcileReferences(options.references);
  }

  get element(): HTMLElement {
    return this.view.dom;
  }

  get composing(): boolean {
    return this.view.composing;
  }

  get focused(): boolean {
    return this.view.hasFocus();
  }

  destroy(): void {
    this.view.destroy();
  }

  focus(): void {
    this.view.focus();
  }

  snapshot(): PromptSnapshot {
    const selection = this.view.state.selection;
    const pendingReferences = (pendingReferenceKey.getState(this.view.state) || []).map(({position, paths, value}) => ({
      position,
      paths: [...paths],
      value,
    }));
    const snapshot: PromptSnapshot = {
      segments: this.segmentsFromDoc(this.view.state.doc),
      anchor: selection.anchor,
      head: selection.head,
    };
    if (pendingReferences.length) snapshot.pendingReferences = pendingReferences;
    if (this.externalDropPosition !== null) snapshot.externalDropPosition = this.externalDropPosition;
    return snapshot;
  }

  linearState(): PromptLinearState {
    const selection = this.view.state.selection;
    return {value: this.serializeForSend().text, anchor: selection.anchor, head: selection.head};
  }

  serializeForSend(): PromptPayload {
    let text = '';
    const referenceIds: string[] = [];
    this.view.state.doc.forEach(node => {
      if (node.isText) text += node.text || '';
      else if (node.type.name === 'hard_break') text += '\n';
      else if (node.type.name === 'file_reference') {
        text += PROMPT_REFERENCE_MARKER;
        referenceIds.push(String(node.attrs.id));
      }
    });
    return {text, referenceIds};
  }

  clear(): void {
    this.externalDropPosition = null;
    let transaction = this.view.state.tr.delete(0, this.view.state.doc.content.size);
    transaction = transaction
      .setSelection(TextSelection.create(transaction.doc, 0))
      .setMeta(pendingReferenceKey, {clear: true} satisfies PendingReferenceMeta)
      .setMeta('addToHistory', false)
      .setMeta(externalSyncKey, true);
    this.view.dispatch(transaction);
  }

  replaceLinearRange(from: number, to: number, text: string): void {
    const size = this.view.state.doc.content.size;
    const start = clamp(Math.min(from, to), 0, size);
    const end = clamp(Math.max(from, to), start, size);
    const content = this.nodesFromText(text);
    const transaction = this.view.state.tr.replaceWith(start, end, Fragment.fromArray(content));
    const position = start + content.reduce((total, node) => total + node.nodeSize, 0);
    transaction.setSelection(TextSelection.create(transaction.doc, position));
    this.view.dispatch(transaction);
  }

  requestReferences(paths: string[], value = PROMPT_REFERENCE_MARKER.repeat(paths.length), from?: number, to?: number, trailingText = ''): void {
    if (!paths.length) return;
    const selection = this.view.state.selection;
    const start = clamp(from ?? selection.from, 0, this.view.state.doc.content.size);
    const end = clamp(to ?? selection.to, start, this.view.state.doc.content.size);
    let transaction = this.view.state.tr.delete(start, end);
    const trailingNodes = this.nodesFromText(trailingText);
    if (trailingNodes.length) transaction = transaction.insert(start, Fragment.fromArray(trailingNodes));
    const cursor = start + trailingNodes.reduce((total, node) => total + node.nodeSize, 0);
    transaction
      .setSelection(TextSelection.create(transaction.doc, cursor))
      .setMeta(pendingReferenceKey, {
        add: {
          token: ++this.pendingSequence,
          position: start,
          paths: [...paths],
          value,
        },
      } satisfies PendingReferenceMeta);
    this.view.dispatch(transaction);
    this.options.onAddReferences(paths);
  }

  requestReferencesAtCoordinates(paths: string[], x: number, y: number): boolean {
    const result = this.view.posAtCoords({left: x, top: y});
    if (!result) return false;
    this.requestReferences(paths, PROMPT_REFERENCE_MARKER.repeat(paths.length), result.pos, result.pos);
    return true;
  }

  rememberExternalDropPosition(x: number, y: number): number | null {
    const result = this.view.posAtCoords({left: x, top: y});
    if (!result) return null;
    this.externalDropPosition = result.pos;
    this.view.dispatch(this.view.state.tr.setSelection(TextSelection.create(this.view.state.doc, result.pos)));
    return result.pos;
  }

  cancelExternalDrop(): void {
    this.externalDropPosition = null;
  }

  selectionRect(): DOMRect | null {
    try {
      const coordinates = this.view.coordsAtPos(this.view.state.selection.head);
      return new DOMRect(coordinates.left, coordinates.top, Math.max(0, coordinates.right - coordinates.left), coordinates.bottom - coordinates.top);
    } catch (_) {
      return null;
    }
  }

  reconcileReferences(nextReferences: PromptFileReference[]): void {
    const next = new Map(nextReferences.map(reference => [reference.id, reference]));
    let transaction = this.view.state.tr
      .setMeta('addToHistory', false)
      .setMeta(externalSyncKey, true);

    // 后端删除或更新引用时，只通过事务修改文档，避免 DOM 与业务状态分叉。
    const existing = this.referenceNodes(transaction.doc);
    for (const entry of [...existing].reverse()) {
      const reference = next.get(entry.reference.id);
      const position = transaction.mapping.map(entry.position);
      if (!reference) {
        transaction.delete(position, position + entry.node.nodeSize);
        continue;
      }
      if (reference.name !== entry.reference.name || reference.path !== entry.reference.path || Boolean(reference.directory) !== Boolean(entry.reference.directory)) {
        transaction.setNodeMarkup(position, undefined, reference);
      }
    }

    const currentIds = new Set(this.referenceNodes(transaction.doc).map(entry => entry.reference.id));
    let added = nextReferences.filter(reference => !currentIds.has(reference.id));

    // 异步返回的引用只消费路径完全匹配的书签，输入期间发生的事务会持续映射该位置。
    const consumedPending: number[] = [];
    const pendingInsertions = pendingReferenceKey.getState(this.view.state) || [];
    for (const pending of pendingInsertions) {
      if (!added.length) break;
      const matched: PromptFileReference[] = [];
      const remaining = [...added];
      for (const path of pending.paths) {
        const matchIndex = remaining.findIndex(reference => reference.path === path);
        if (matchIndex < 0) break;
        matched.push(remaining.splice(matchIndex, 1)[0]);
      }
      if (matched.length !== pending.paths.length) {
        continue;
      }
      const position = transaction.mapping.map(pending.position, -1);
      const nodes = this.nodesFromTemplate(pending.value, matched);
      transaction.insert(position, Fragment.fromArray(nodes));
      added = remaining;
      consumedPending.push(pending.token);
    }
    if (consumedPending.length) {
      transaction.setMeta(pendingReferenceKey, {consume: consumedPending} satisfies PendingReferenceMeta);
    }

    if (added.length && this.externalDropPosition !== null) {
      const position = transaction.mapping.map(this.externalDropPosition);
      transaction.insert(position, Fragment.fromArray(added.map(reference => promptSchema.nodes.file_reference.create(reference))));
      added = [];
      this.externalDropPosition = null;
    }

    if (added.length) {
      const nodes = added.map(reference => promptSchema.nodes.file_reference.create(reference));
      transaction.insert(transaction.doc.content.size, Fragment.fromArray(nodes));
    }

    this.references = next;
    if (transaction.docChanged) {
      this.view.dispatch(transaction);
      const documentReferences = this.referencesInDocument();
      if (!this.sameReferenceOrder(documentReferences, nextReferences)) {
        this.options.onReferencesChange(documentReferences);
      }
    } else this.options.onUpdate?.();
  }

  removeReference(id: string): boolean {
    const entry = this.referenceNodes().find(item => item.reference.id === id);
    if (!entry) return false;
    this.view.dispatch(this.view.state.tr.delete(entry.position, entry.position + entry.node.nodeSize));
    return true;
  }

  cutReference(id: string): void {
    const entry = this.referenceNodes().find(item => item.reference.id === id);
    if (!entry) return;
    this.focus();
    this.view.dispatch(this.view.state.tr.setSelection(NodeSelection.create(this.view.state.doc, entry.position)));
    if (!document.execCommand('cut')) {
      navigator.clipboard?.writeText(`@${entry.reference.path} `).catch(() => undefined);
      this.removeReference(id);
    }
  }

  private dispatchTransaction(transaction: Transaction): void {
    const previousIds = this.referenceNodes(this.view.state.doc).map(entry => entry.reference.id);
    if (this.externalDropPosition !== null) this.externalDropPosition = transaction.mapping.map(this.externalDropPosition);
    const state = this.view.state.apply(transaction);
    this.view.updateState(state);
    this.syncDomState();
    const nextReferences = this.referencesInDocument(state.doc);
    const nextIds = nextReferences.map(reference => reference.id);

    if (transaction.docChanged && transaction.getMeta(externalSyncKey) !== true) {
      if (previousIds.length !== nextIds.length || previousIds.some((id, index) => id !== nextIds[index])) {
        this.options.onReferencesChange(nextReferences);
      }
    }
    if (transaction.docChanged) this.options.onChange(this.snapshot(), this.serializeForSend(), nextReferences);
    this.options.onUpdate?.();
  }

  private handleKeyDown(event: KeyboardEvent): boolean {
    if (this.options.onKeyDown?.(event)) return true;
    if (event.isComposing || event.keyCode === 229) return false;
    if (event.key === 'Enter') {
      event.preventDefault();
      if (this.options.shouldSubmit(event)) this.options.onSubmit();
      else this.insertHardBreak();
      return true;
    }
    if ((event.key === 'ArrowLeft' || event.key === 'ArrowRight') && !event.altKey && !event.ctrlKey && !event.metaKey) {
      return this.moveAcrossReference(event.key === 'ArrowRight' ? 1 : -1, event.shiftKey);
    }
    if (event.key === 'Backspace' || event.key === 'Delete') return this.deleteAdjacentReference(event.key === 'Delete' ? 1 : -1);
    return false;
  }

  private moveAcrossReference(direction: -1 | 1, extend: boolean): boolean {
    const {doc, selection} = this.view.state;
    let head = selection.head;
    if (selection instanceof NodeSelection && selection.node.type.name === 'file_reference') {
      head = direction > 0 ? selection.to : selection.from;
    }
    const resolved = doc.resolve(head);
    const adjacent = direction > 0 ? resolved.nodeAfter : resolved.nodeBefore;
    if (adjacent?.type.name !== 'file_reference') return false;
    const target = head + direction * adjacent.nodeSize;
    const nextSelection = extend
      ? TextSelection.create(doc, selection.anchor, target)
      : TextSelection.create(doc, target);
    this.view.dispatch(this.view.state.tr.setSelection(nextSelection).scrollIntoView());
    return true;
  }

  private deleteAdjacentReference(direction: -1 | 1): boolean {
    const {doc, selection} = this.view.state;
    if (selection instanceof NodeSelection && selection.node.type.name === 'file_reference') {
      this.view.dispatch(this.view.state.tr.deleteSelection().scrollIntoView());
      return true;
    }
    if (!selection.empty) return false;
    const resolved = doc.resolve(selection.head);
    const adjacent = direction > 0 ? resolved.nodeAfter : resolved.nodeBefore;
    if (adjacent?.type.name !== 'file_reference') return false;
    const from = direction > 0 ? selection.head : selection.head - adjacent.nodeSize;
    this.view.dispatch(this.view.state.tr.delete(from, from + adjacent.nodeSize).scrollIntoView());
    return true;
  }

  private insertHardBreak(): void {
    const {from, to} = this.view.state.selection;
    const node = promptSchema.nodes.hard_break.create();
    const transaction = this.view.state.tr.replaceWith(from, to, node);
    transaction.setSelection(TextSelection.create(transaction.doc, from + node.nodeSize));
    this.view.dispatch(transaction.scrollIntoView());
  }

  private handleCopy(event: ClipboardEvent, cut: boolean): boolean {
    const {selection, doc} = this.view.state;
    if (selection.empty) return false;
    const slice = doc.slice(selection.from, selection.to).content;
    const payload = this.clipboardPayload(slice);
    if (!payload.referenceIds.length) return false;
    event.preventDefault();
    event.clipboardData?.setData('text/plain', payload.plain);
    event.clipboardData?.setData('application/x-codex-file-references', JSON.stringify(payload));
    const encoded = encodeURIComponent(JSON.stringify(payload));
    event.clipboardData?.setData('text/html', `<span data-codex-file-references="${encoded.replace(/&/g, '&amp;').replace(/"/g, '&quot;')}">${this.escapeHtml(payload.plain).replace(/\n/g, '<br>')}</span>`);
    if (cut) this.view.dispatch(this.view.state.tr.deleteSelection().scrollIntoView());
    return true;
  }

  private handlePaste(event: ClipboardEvent): boolean {
    const payload = this.readClipboardPayload(event);
    if (!payload) return false;
    event.preventDefault();
    this.requestReferences(payload.paths, payload.text, this.view.state.selection.from, this.view.state.selection.to);
    return true;
  }

  private readClipboardPayload(event: ClipboardEvent): PromptClipboardPayload | null {
    const custom = event.clipboardData?.getData('application/x-codex-file-references');
    if (custom) {
      try {
        const payload = JSON.parse(custom) as Partial<PromptClipboardPayload> & {value?: string};
        const text = String(payload.text ?? payload.value ?? '');
        const paths = Array.isArray(payload.paths) ? payload.paths.map(String) : [];
        if (paths.length && [...text].filter(character => character === PROMPT_REFERENCE_MARKER).length === paths.length) {
          return {text, referenceIds: [], paths, plain: String(payload.plain || '')};
        }
      } catch (_) {}
    }

    const html = event.clipboardData?.getData('text/html');
    if (html) {
      try {
        const encoded = new DOMParser().parseFromString(html, 'text/html')
          .querySelector('[data-codex-file-references]')?.getAttribute('data-codex-file-references');
        if (encoded) {
          const payload = JSON.parse(decodeURIComponent(encoded)) as Partial<PromptClipboardPayload> & {value?: string};
          const text = String(payload.text ?? payload.value ?? '');
          const paths = Array.isArray(payload.paths) ? payload.paths.map(String) : [];
          if (paths.length && [...text].filter(character => character === PROMPT_REFERENCE_MARKER).length === paths.length) {
            return {text, referenceIds: [], paths, plain: String(payload.plain || '')};
          }
        }
      } catch (_) {}
    }

    const plain = event.clipboardData?.getData('text/plain') || '';
    const match = /^@((?:[a-zA-Z]:[\\/]|\\\\|\/).+)$/.exec(plain.trim());
    return match ? {text: PROMPT_REFERENCE_MARKER, referenceIds: [], paths: [match[1]], plain} : null;
  }

  private clipboardPayload(fragment: Fragment): PromptClipboardPayload {
    let text = '';
    let plain = '';
    const referenceIds: string[] = [];
    const paths: string[] = [];
    fragment.forEach(node => {
      if (node.isText) {
        text += node.text || '';
        plain += node.text || '';
      } else if (node.type.name === 'hard_break') {
        text += '\n';
        plain += '\n';
      } else if (node.type.name === 'file_reference') {
        const reference = referenceFromNode(node);
        text += PROMPT_REFERENCE_MARKER;
        plain += `@${reference.path} `;
        referenceIds.push(reference.id);
        paths.push(reference.path);
      }
    });
    return {text, referenceIds, paths, plain};
  }

  private createReferenceNodeView(node: ProseMirrorNode, view: EditorView, getPos: () => number | undefined): NodeView {
    const reference = referenceFromNode(node);
    const dom = document.createElement('span');
    dom.className = 'file-reference-node';
    dom.dataset.fileReference = reference.id;
    dom.dataset.fileReferenceName = reference.name;
    dom.title = reference.path;
    dom.contentEditable = 'false';
    dom.draggable = true;

    // 原子节点外层保留透明排版余量，避免 Chromium 将边界光标画进可视标签。
    const chip = document.createElement('span');
    chip.className = `file-reference-chip${reference.directory ? ' directory' : ''}`;

    const icon = document.createElement('span');
    icon.className = 'file-reference-icon';
    icon.innerHTML = fileIcon(Boolean(reference.directory));
    const name = document.createElement('span');
    name.className = 'file-reference-name';
    name.textContent = reference.name;
    const remove = document.createElement('button');
    remove.className = 'file-reference-remove';
    remove.type = 'button';
    remove.tabIndex = -1;
    remove.title = '移除文件引用';
    remove.setAttribute('aria-label', `移除 ${reference.name}`);
    remove.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"></path></svg>';
    remove.addEventListener('mousedown', event => event.preventDefault());
    remove.addEventListener('click', event => {
      event.preventDefault();
      event.stopPropagation();
      const position = getPos();
      if (typeof position === 'number') view.dispatch(view.state.tr.delete(position, position + node.nodeSize));
    });
    dom.addEventListener('contextmenu', event => {
      event.preventDefault();
      event.stopPropagation();
      this.options.onContextMenu(reference, event.clientX, event.clientY);
    });
    chip.append(icon, name, remove);
    dom.append(chip);
    return {
      dom,
      stopEvent: event => event.target === remove || event.type === 'contextmenu',
    };
  }

  private docFromSegments(segments: PromptSegment[]): ProseMirrorNode {
    const nodes: ProseMirrorNode[] = [];
    for (const segment of segments) {
      if (segment.type === 'text') nodes.push(...this.nodesFromText(segment.text));
      else {
        const reference = this.references.get(segment.referenceId) || {
          id: segment.referenceId,
          name: segment.referencePath?.split(/[\\/]/).pop() || '文件',
          path: segment.referencePath || '',
          directory: false,
        };
        nodes.push(promptSchema.nodes.file_reference.create(reference));
      }
    }
    return promptSchema.topNodeType.create(null, Fragment.fromArray(nodes));
  }

  private segmentsFromDoc(doc: ProseMirrorNode): PromptSegment[] {
    const segments: PromptSegment[] = [];
    doc.forEach(node => {
      if (node.isText) appendTextSegment(segments, node.text || '');
      else if (node.type.name === 'hard_break') appendTextSegment(segments, '\n');
      else if (node.type.name === 'file_reference') {
        segments.push({type: 'reference', referenceId: String(node.attrs.id), referencePath: String(node.attrs.path || '')});
      }
    });
    return segments;
  }

  private nodesFromText(text: string): ProseMirrorNode[] {
    const nodes: ProseMirrorNode[] = [];
    String(text || '').split('\n').forEach((line, index) => {
      if (index) nodes.push(promptSchema.nodes.hard_break.create());
      if (line) nodes.push(promptSchema.text(line));
    });
    return nodes;
  }

  private nodesFromTemplate(value: string, references: PromptFileReference[]): ProseMirrorNode[] {
    const nodes: ProseMirrorNode[] = [];
    let referenceIndex = 0;
    String(value || '').split(PROMPT_REFERENCE_MARKER).forEach((part, index, parts) => {
      nodes.push(...this.nodesFromText(part));
      if (index < parts.length - 1 && references[referenceIndex]) {
        nodes.push(promptSchema.nodes.file_reference.create(references[referenceIndex++]));
      }
    });
    while (referenceIndex < references.length) nodes.push(promptSchema.nodes.file_reference.create(references[referenceIndex++]));
    return nodes;
  }

  private referenceNodes(doc = this.view.state.doc): Array<{node: ProseMirrorNode; position: number; reference: PromptFileReference}> {
    const result: Array<{node: ProseMirrorNode; position: number; reference: PromptFileReference}> = [];
    doc.forEach((node, position) => {
      if (node.type.name === 'file_reference') result.push({node, position, reference: referenceFromNode(node)});
    });
    return result;
  }

  private referencesInDocument(doc = this.view.state.doc): PromptFileReference[] {
    return this.referenceNodes(doc).map(entry => entry.reference);
  }

  private sameReferenceOrder(left: PromptFileReference[], right: PromptFileReference[]): boolean {
    return left.length === right.length && left.every((reference, index) => reference.id === right[index].id);
  }

  private syncDomState(): void {
    this.view.dom.dataset.empty = String(this.view.state.doc.content.size === 0);
    this.view.dom.dataset.selectionAnchor = String(this.view.state.selection.anchor);
    this.view.dom.dataset.selectionHead = String(this.view.state.selection.head);
  }

  private escapeHtml(value: string): string {
    return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
}
