export const BRIDGE_VERSION = 1 as const;

export const BRIDGE_COMMAND_TYPES = [
  'ready', 'reconnect', 'send', 'stop', 'new', 'closeSession', 'activateSession',
  'history', 'openThread', 'rename', 'export', 'pickFile', 'pickImage', 'dropFiles',
  'cancelDrop', 'composerBounds', 'listProjectFiles', 'removeAttachment', 'removeFileReference',
  'removeFileReferences', 'addFileReferences', 'reorderFileReferences', 'acceptChange',
  'revertChange', 'acceptAll', 'revertAll', 'openChange', 'compact', 'review', 'rewind',
  'mcp', 'usage', 'setting', 'selectProvider', 'activateProviderProfile', 'saveProviderProfile',
  'deleteProviderProfile', 'checkProviders', 'behaviorSetting', 'browseNotificationSound',
  'testNotificationSound', 'toggleStreaming', 'toggleThinking', 'saveInstructions', 'savePrompt',
  'deletePrompt', 'selectPrompt', 'saveAgent', 'deleteAgent', 'selectAgent', 'loadMcp',
  'reloadMcp', 'loadSkills', 'reloadSkills', 'setSkillEnabled', 'importSkill', 'openSkill',
  'openMcpConfig', 'loginMcp', 'saveMcp', 'deleteMcp', 'setMcpEnabled', 'copyText',
  'answerQuestions', 'cancelQuestions', 'conversationSearch', 'openFile', 'openUrl', 'openSettings',
] as const;

export const BRIDGE_EVENT_TYPES = [
  'bootstrap', 'connection', 'busy', 'queue', 'clear', 'message', 'replaceMessage',
  'appendMessage', 'history', 'changes', 'attachments', 'fileReferences', 'fileContext',
  'projectFiles', 'usage', 'thread', 'providers', 'mcpServers', 'mcpLog', 'skillEnabled',
  'skills', 'question', 'toast', 'nativeDrag', 'nativeDrop', 'protocol.error',
] as const;

export type BridgeCommandType = typeof BRIDGE_COMMAND_TYPES[number];
export type BridgeEventType = typeof BRIDGE_EVENT_TYPES[number];

export interface BridgeEnvelope<TType extends string, TPayload extends object> {
  v: typeof BRIDGE_VERSION;
  type: TType;
  requestId: string;
  sessionId: string;
  turnId: string;
  generation: number;
  payload: TPayload;
}

type CommandPayload<T extends BridgeCommandType> =
  T extends 'send' ? {text: string} :
  T extends 'new' ? {title?: string; skipConfirmation?: boolean} :
  T extends 'listProjectFiles' ? {query: string} :
  T extends 'answerQuestions' ? {answers: Record<string, {answers: string[]}>} :
  Record<string, unknown>;

type EventPayload<T extends BridgeEventType> =
  T extends 'appendMessage' ? {itemId: string; kind: string; title: string; delta: string} :
  T extends 'projectFiles' ? {items: Array<{name: string; path: string}>} :
  T extends 'toast' ? {message: string; providerSaveSuccess?: boolean} :
  T extends 'protocol.error' ? {code: string; message: string; receivedType?: string} :
  Record<string, unknown>;

export type BridgeCommand = {
  [T in BridgeCommandType]: BridgeEnvelope<T, CommandPayload<T>>
}[BridgeCommandType];

export type BridgeEvent = {
  [T in BridgeEventType]: BridgeEnvelope<T, EventPayload<T>>
}[BridgeEventType];
