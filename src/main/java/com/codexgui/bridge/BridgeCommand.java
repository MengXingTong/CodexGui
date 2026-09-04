package com.codexgui.bridge;

import com.codexgui.conversation.SessionId;
import com.google.gson.JsonObject;

import java.util.Arrays;

public sealed interface BridgeCommand permits BridgeCommand.V1 {
    int version();
    Type type();
    String requestId();
    SessionId sessionId();
    String turnId();
    long generation();
    JsonObject payload();
    boolean legacy();

    record V1(
        Type type,
        String requestId,
        SessionId sessionId,
        String turnId,
        long generation,
        JsonObject payload,
        boolean legacy
    ) implements BridgeCommand {
        @Override public int version() { return 1; }
    }

    enum Type {
        READY("ready"),
        RECONNECT("reconnect"),
        SEND("send"),
        STOP("stop"),
        NEW("new"),
        CLOSE_SESSION("closeSession"),
        ACTIVATE_SESSION("activateSession"),
        HISTORY("history"),
        OPEN_THREAD("openThread"),
        RENAME("rename"),
        EXPORT("export"),
        PICK_FILE("pickFile"),
        PICK_IMAGE("pickImage"),
        DROP_FILES("dropFiles"),
        CANCEL_DROP("cancelDrop"),
        COMPOSER_BOUNDS("composerBounds"),
        LIST_PROJECT_FILES("listProjectFiles"),
        REMOVE_ATTACHMENT("removeAttachment"),
        REMOVE_FILE_REFERENCE("removeFileReference"),
        REMOVE_FILE_REFERENCES("removeFileReferences"),
        ADD_FILE_REFERENCES("addFileReferences"),
        REORDER_FILE_REFERENCES("reorderFileReferences"),
        ACCEPT_CHANGE("acceptChange"),
        REVERT_CHANGE("revertChange"),
        ACCEPT_ALL("acceptAll"),
        REVERT_ALL("revertAll"),
        OPEN_CHANGE("openChange"),
        COMPACT("compact"),
        REVIEW("review"),
        REWIND("rewind"),
        MCP("mcp"),
        USAGE("usage"),
        SETTING("setting"),
        SELECT_PROVIDER("selectProvider"),
        ACTIVATE_PROVIDER_PROFILE("activateProviderProfile"),
        SAVE_PROVIDER_PROFILE("saveProviderProfile"),
        DELETE_PROVIDER_PROFILE("deleteProviderProfile"),
        CHECK_PROVIDERS("checkProviders"),
        BEHAVIOR_SETTING("behaviorSetting"),
        BROWSE_NOTIFICATION_SOUND("browseNotificationSound"),
        TEST_NOTIFICATION_SOUND("testNotificationSound"),
        TOGGLE_STREAMING("toggleStreaming"),
        TOGGLE_THINKING("toggleThinking"),
        SAVE_INSTRUCTIONS("saveInstructions"),
        SAVE_PROMPT("savePrompt"),
        DELETE_PROMPT("deletePrompt"),
        SELECT_PROMPT("selectPrompt"),
        SAVE_AGENT("saveAgent"),
        DELETE_AGENT("deleteAgent"),
        SELECT_AGENT("selectAgent"),
        LOAD_MCP("loadMcp"),
        RELOAD_MCP("reloadMcp"),
        LOAD_SKILLS("loadSkills"),
        RELOAD_SKILLS("reloadSkills"),
        SET_SKILL_ENABLED("setSkillEnabled"),
        IMPORT_SKILL("importSkill"),
        OPEN_SKILL("openSkill"),
        OPEN_MCP_CONFIG("openMcpConfig"),
        LOGIN_MCP("loginMcp"),
        SAVE_MCP("saveMcp"),
        DELETE_MCP("deleteMcp"),
        SET_MCP_ENABLED("setMcpEnabled"),
        COPY_TEXT("copyText"),
        ANSWER_QUESTIONS("answerQuestions"),
        CANCEL_QUESTIONS("cancelQuestions"),
        CONVERSATION_SEARCH("conversationSearch"),
        OPEN_FILE("openFile"),
        OPEN_URL("openUrl"),
        OPEN_SETTINGS("openSettings");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() { return wireName; }

        public static Type fromWireName(String value) {
            return Arrays.stream(values()).filter(type -> type.wireName.equals(value)).findFirst().orElse(null);
        }
    }
}
