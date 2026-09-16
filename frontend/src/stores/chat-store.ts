import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { Conversation, Message, Source } from "@/types/chat";

function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).substring(2);
}

function generateTitle(content: string): string {
  const trimmed = content.trim();
  if (trimmed.length <= 40) return trimmed;
  return trimmed.substring(0, 40) + "...";
}

interface ChatState {
  conversations: Conversation[];
  activeConversationId: string | null;
  isStreaming: boolean;

  // Actions
  createConversation: () => string;
  deleteConversation: (id: string) => void;
  renameConversation: (id: string, title: string) => void;
  setActiveConversation: (id: string | null) => void;
  addMessage: (conversationId: string, message: Message) => void;
  updateStreamingMessage: (
    conversationId: string,
    messageId: string,
    content: string
  ) => void;
  appendStreamingContent: (
    conversationId: string,
    messageId: string,
    chunk: string
  ) => void;
  setMessageSources: (
    conversationId: string,
    messageId: string,
    sources: Source[]
  ) => void;
  setStreaming: (streaming: boolean) => void;
  getActiveConversation: () => Conversation | undefined;
  clearAllConversations: () => void;
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get) => ({
      conversations: [],
      activeConversationId: null,
      isStreaming: false,

      createConversation: () => {
        const id = generateId();
        const newConversation: Conversation = {
          id,
          title: "Cuộc trò chuyện mới",
          messages: [],
          createdAt: new Date(),
          updatedAt: new Date(),
        };
        set((state) => ({
          conversations: [newConversation, ...state.conversations],
          activeConversationId: id,
        }));
        return id;
      },

      deleteConversation: (id: string) => {
        set((state) => {
          const filtered = state.conversations.filter((c) => c.id !== id);
          const newActiveId =
            state.activeConversationId === id
              ? filtered.length > 0
                ? filtered[0].id
                : null
              : state.activeConversationId;
          return {
            conversations: filtered,
            activeConversationId: newActiveId,
          };
        });
      },

      renameConversation: (id: string, title: string) => {
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === id ? { ...c, title, updatedAt: new Date() } : c
          ),
        }));
      },

      setActiveConversation: (id: string | null) => {
        set({ activeConversationId: id });
      },

      addMessage: (conversationId: string, message: Message) => {
        set((state) => ({
          conversations: state.conversations.map((c) => {
            if (c.id !== conversationId) return c;
            const isFirstUserMessage =
              message.role === "user" && c.messages.length === 0;
            return {
              ...c,
              messages: [...c.messages, message],
              title: isFirstUserMessage
                ? generateTitle(message.content)
                : c.title,
              updatedAt: new Date(),
            };
          }),
        }));
      },

      updateStreamingMessage: (
        conversationId: string,
        messageId: string,
        content: string
      ) => {
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === conversationId
              ? {
                  ...c,
                  messages: c.messages.map((m) =>
                    m.id === messageId ? { ...m, content } : m
                  ),
                  updatedAt: new Date(),
                }
              : c
          ),
        }));
      },

      appendStreamingContent: (
        conversationId: string,
        messageId: string,
        chunk: string
      ) => {
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === conversationId
              ? {
                  ...c,
                  messages: c.messages.map((m) =>
                    m.id === messageId
                      ? { ...m, content: m.content + chunk }
                      : m
                  ),
                  updatedAt: new Date(),
                }
              : c
          ),
        }));
      },

      setMessageSources: (
        conversationId: string,
        messageId: string,
        sources: Source[]
      ) => {
        set((state) => ({
          conversations: state.conversations.map((c) =>
            c.id === conversationId
              ? {
                  ...c,
                  messages: c.messages.map((m) =>
                    m.id === messageId ? { ...m, sources } : m
                  ),
                }
              : c
          ),
        }));
      },

      setStreaming: (streaming: boolean) => {
        set({ isStreaming: streaming });
      },

      getActiveConversation: () => {
        const state = get();
        return state.conversations.find(
          (c) => c.id === state.activeConversationId
        );
      },

      clearAllConversations: () => {
        set({ conversations: [], activeConversationId: null });
      },
    }),
    {
      name: "utc-chatbot-storage",
      partialize: (state) => ({
        conversations: state.conversations,
        activeConversationId: state.activeConversationId,
      }),
      onRehydrateStorage: () => (state) => {
        // Rehydrate Date objects from JSON strings
        if (state) {
          state.conversations = state.conversations.map((c) => ({
            ...c,
            createdAt: new Date(c.createdAt),
            updatedAt: new Date(c.updatedAt),
            messages: c.messages.map((m) => ({
              ...m,
              timestamp: new Date(m.timestamp),
            })),
          }));
        }
      },
    }
  )
);
