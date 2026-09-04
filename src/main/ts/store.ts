export interface ConversationMessage {
  kind: string;
  title: string;
  body: string;
  itemId?: string;
  elapsedMs?: number;
  fileReferencePaths?: string[];
}

export interface BridgeState {
  [key: string]: unknown;
  messages: ConversationMessage[];
  connected?: boolean;
  busy?: boolean;
  queuedCount?: number;
  title?: unknown;
  threadId?: unknown;
  history?: unknown[];
  changes?: unknown[];
  attachments?: unknown[];
  fileReferences?: unknown[];
  providers?: unknown[];
  skills?: unknown[];
  skillErrors?: unknown[];
  usagePercentage?: number;
  usageUsedTokens?: number;
  usageMaxTokens?: number;
  visibleMessageCount?: number;
  view?: string;
}

export interface ReducedEvent {
  type: string;
  [key: string]: unknown;
}

export interface Store<TState, TEvent> {
  getState(): TState;
  dispatch(event: TEvent): TState;
}

export function createStore<TState, TEvent>(
  initialState: TState,
  reducer: (state: TState, event: TEvent) => TState,
): Store<TState, TEvent> {
  let state = initialState;
  return {
    getState: () => state,
    dispatch(event) {
      state = reducer(state, event);
      return state;
    },
  };
}

export function reduceBridgeEvent(state: BridgeState, event: ReducedEvent): BridgeState {
  switch (event.type) {
    case 'bootstrap':
      Object.assign(state, object(event.state));
      break;
    case 'connection':
      state.connected = Boolean(event.connected);
      break;
    case 'busy':
      state.busy = Boolean(event.busy);
      state.queuedCount = nonNegative(event.queuedCount);
      break;
    case 'queue':
      state.queuedCount = nonNegative(event.queuedCount);
      break;
    case 'clear':
      state.messages = [];
      state.title = string(event.title) || '新会话';
      state.threadId = event.threadId || null;
      state.usagePercentage = 0;
      state.usageUsedTokens = 0;
      state.usageMaxTokens = 0;
      state.visibleMessageCount = 100;
      break;
    case 'message':
      state.messages.push(event.entry as ConversationMessage);
      break;
    case 'replaceMessage':
      replaceMessage(state.messages, event.entry as ConversationMessage);
      break;
    case 'appendMessage':
      appendMessage(state.messages, event);
      break;
    case 'history': state.history = array(event.items); break;
    case 'changes': state.changes = array(event.items); break;
    case 'attachments': state.attachments = array(event.items); break;
    case 'fileReferences': state.fileReferences = array(event.items); break;
    case 'providers': state.providers = array(event.items); break;
    case 'skills':
      state.skills = array(event.items);
      state.skillErrors = array(event.errors);
      break;
    case 'usage':
      state.usagePercentage = number(event.percentage);
      state.usageUsedTokens = number(event.usedTokens);
      state.usageMaxTokens = number(event.maxTokens);
      break;
    case 'thread':
      state.threadId = event.id || null;
      state.title = string(event.title) || state.title;
      state.view = 'chat';
      break;
    default:
      break;
  }
  return state;
}

export interface StreamAppendEvent extends ReducedEvent {
  type: 'appendMessage';
  sessionId: string;
  itemId: string;
  delta: string;
}

export function createStreamBatcher(
  schedule: (flush: () => void) => void,
  sink: (event: StreamAppendEvent) => void,
) {
  const pending = new Map<string, StreamAppendEvent>();
  let scheduled = false;

  const flush = () => {
    scheduled = false;
    const events = [...pending.values()];
    pending.clear();
    events.forEach(sink);
  };

  return {
    push(event: StreamAppendEvent) {
      const key = `${event.sessionId}\0${event.itemId}`;
      const current = pending.get(key);
      pending.set(key, current ? {...event, delta: current.delta + event.delta} : {...event});
      if (scheduled) return;
      scheduled = true;
      schedule(flush);
    },
    flush,
    size: () => pending.size,
  };
}

function replaceMessage(messages: ConversationMessage[], entry: ConversationMessage) {
  const index = messages.findIndex(message => message.itemId === entry.itemId);
  if (index >= 0) messages[index] = entry;
  else messages.push(entry);
}

function appendMessage(messages: ConversationMessage[], event: ReducedEvent) {
  const itemId = string(event.itemId);
  const index = messages.findIndex(message => message.itemId === itemId);
  if (index >= 0) {
    messages[index] = {...messages[index], body: messages[index].body + string(event.delta)};
    return;
  }
  messages.push({
    kind: string(event.kind) || 'assistant',
    title: string(event.title) || 'Codex',
    body: string(event.delta),
    itemId,
  });
}

function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

function array(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function string(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function number(value: unknown): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function nonNegative(value: unknown): number {
  return Math.max(0, number(value));
}
