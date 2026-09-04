import {describe, expect, it} from 'vitest';
import {createStore, createStreamBatcher, reduceBridgeEvent, type BridgeState} from '../../main/ts/store';

describe('bridge store', () => {
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
});
