"use client";

import { useCallback, useRef } from "react";
import { useChatStore } from "@/stores/chat-store";
import { sendMockSSEMessage, sendSSEMessage } from "@/lib/sse-client";
import type { Message } from "@/types/chat";

function generateId(): string {
  return Date.now().toString(36) + Math.random().toString(36).substring(2);
}

const USE_MOCK = process.env.NEXT_PUBLIC_USE_MOCK !== "false";

export function useChat() {
  const abortControllerRef = useRef<AbortController | null>(null);

  const {
    conversations,
    activeConversationId,
    isStreaming,
    createConversation,
    addMessage,
    appendStreamingContent,
    setMessageSources,
    setStreaming,
    getActiveConversation,
  } = useChatStore();

  const activeConversation = conversations.find(
    (c) => c.id === activeConversationId
  );

  const sendMessage = useCallback(
    async (content: string) => {
      if (!content.trim() || isStreaming) return;

      let conversationId = activeConversationId;
      if (!conversationId) {
        conversationId = createConversation();
      }

      // Add user message
      const userMessage: Message = {
        id: generateId(),
        role: "user",
        content: content.trim(),
        timestamp: new Date(),
      };
      addMessage(conversationId, userMessage);

      // Create assistant placeholder message
      const assistantMessageId = generateId();
      const assistantMessage: Message = {
        id: assistantMessageId,
        role: "assistant",
        content: "",
        timestamp: new Date(),
      };
      addMessage(conversationId, assistantMessage);

      setStreaming(true);

      const sseFunction = USE_MOCK ? sendMockSSEMessage : sendSSEMessage;

      abortControllerRef.current = sseFunction(
        content.trim(),
        conversationId,
        {
          onContent: (chunk: string) => {
            appendStreamingContent(conversationId!, assistantMessageId, chunk);
          },
          onSources: (sources) => {
            setMessageSources(conversationId!, assistantMessageId, sources);
          },
          onDone: () => {
            setStreaming(false);
            abortControllerRef.current = null;
          },
          onError: (error: string) => {
            appendStreamingContent(
              conversationId!,
              assistantMessageId,
              `\n\n⚠️ **Lỗi:** ${error}`
            );
            setStreaming(false);
            abortControllerRef.current = null;
          },
        }
      );
    },
    [
      activeConversationId,
      isStreaming,
      createConversation,
      addMessage,
      appendStreamingContent,
      setMessageSources,
      setStreaming,
    ]
  );

  const stopStreaming = useCallback(() => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
      abortControllerRef.current = null;
      setStreaming(false);
    }
  }, [setStreaming]);

  return {
    activeConversation,
    messages: activeConversation?.messages || [],
    isStreaming,
    sendMessage,
    stopStreaming,
    getActiveConversation,
  };
}
