import {describe, expect, it} from 'vitest';
import {
  copyStateFields,
  createStore,
  createStreamBatcher,
  reduceBridgeEvent,
  isProviderChange,
  type BridgeState,
} from '../../main/ts/store';

describe('bridge store', () => {
  it('copies session fields without overwriting the tab prompt snapshot', () => {
    const promptSnapshot = [{text: '后台页签草稿'}];
    const tab: Record<string, unknown> = {messages: [], busy: false, promptSnapshot};
    const state: Record<string, unknown> = {
      messages: [{kind: 'assistant', title: 'Codex', body: '完成'}],
      busy: true,
      promptSnapshot: [{text: '当前页签输入'}],
    };

    copyStateFields(tab, state, ['messages', 'busy']);

    expect(tab.messages).toBe(state.messages);
    expect(tab.busy).toBe(true);
    expect(tab.promptSnapshot).toBe(promptSnapshot);
  });

  it('updates only the addressed message', () => {
    const state: BridgeState = {
      messages: [
        {kind: 'assistant', title: 'Codex', body: 'A', itemId: 'a'},
        {kind: 'assistant', title: 'Codex', body: 'B', itemId: 'b'},
      ],
    };
    const untouched = state.messages[1];
    const store = createStore(state, reduceBridgeEvent);

    store.dispatch({type: 'appendMessage', itemId: 'a', delta: '1'});

    expect(store.getState().messages[0].body).toBe('A1');
    expect(store.getState().messages[1]).toBe(untouched);
  });

  it('keeps the first timestamp while replacing and appending a streamed reply', () => {
    const state: BridgeState = {messages: []};
    const store = createStore(state, reduceBridgeEvent);

    store.dispatch({
      type: 'message',
      entry: {kind: 'assistant', title: 'Codex', body: '开', itemId: 'reply', createdAtEpochMs: 1_700_000_000_000},
    });
    store.dispatch({
      type: 'replaceMessage',
      entry: {kind: 'assistant', title: 'Codex', body: '开始', itemId: 'reply'},
    });
    store.dispatch({type: 'appendMessage', itemId: 'reply', delta: '回复'});

    expect(store.getState().messages[0]).toMatchObject({
      body: '开始回复',
      createdAtEpochMs: 1_700_000_000_000,
    });
  });

  it('batches streaming deltas by session and item', () => {
    const callbacks: Array<() => void> = [];
    const received: Array<{sessionId: string; itemId: string; delta: string}> = [];
    const batcher = createStreamBatcher(callback => callbacks.push(callback), event => received.push(event));

    batcher.push({type: 'appendMessage', sessionId: 'a', itemId: 'm', delta: '你'});
    batcher.push({type: 'appendMessage', sessionId: 'a', itemId: 'm', delta: '好'});
    batcher.push({type: 'appendMessage', sessionId: 'b', itemId: 'm', delta: '!'});
    callbacks[0]();

    expect(received).toEqual([
      expect.objectContaining({sessionId: 'a', itemId: 'm', delta: '你好'}),
      expect.objectContaining({sessionId: 'b', itemId: 'm', delta: '!'}),
    ]);
  });

  it('does not redeliver deltas after an early flush', () => {
    const callbacks: Array<() => void> = [];
    const received: string[] = [];
    const batcher = createStreamBatcher(callback => callbacks.push(callback), event => received.push(event.delta));

    batcher.push({type: 'appendMessage', sessionId: 'a', itemId: 'command', delta: '最后输出'});
    batcher.flush();
    callbacks[0]();

    expect(received).toEqual(['最后输出']);
    expect(batcher.size()).toBe(0);
  });

  it('detects conversation provider changes', () => {
    expect(isProviderChange('codex', 'claude')).toBe(true);
    expect(isProviderChange('claude', 'codex')).toBe(true);
    expect(isProviderChange('codex', 'codex')).toBe(false);
    expect(isProviderChange('claude', 'claude')).toBe(false);
  });

  it('stores history with the provider that produced it', () => {
    const state: BridgeState = {messages: [], history: [{id: 'old'}], historyProvider: 'codex'};
    const store = createStore(state, reduceBridgeEvent);

    store.dispatch({type: 'history', provider: 'claude', items: []});

    expect(store.getState().history).toEqual([]);
    expect(store.getState().historyProvider).toBe('claude');
  });
});
