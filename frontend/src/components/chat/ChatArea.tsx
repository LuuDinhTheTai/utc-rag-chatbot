"use client";

import { useEffect, useRef } from "react";
import { GraduationCap } from "lucide-react";
import { ScrollArea } from "@/components/ui/scroll-area";
import { useChat } from "@/hooks/use-chat";
import { ChatMessage } from "./ChatMessage";
import { ChatInput } from "./ChatInput";
import { WelcomeScreen } from "./WelcomeScreen";
import { TypingIndicator } from "./TypingIndicator";
import { ThemeToggle } from "@/components/theme-toggle";

interface ChatAreaProps {
  onToggleSidebar?: () => void;
}

export function ChatArea({ onToggleSidebar: _onToggleSidebar }: ChatAreaProps) {
  const { messages, isStreaming, sendMessage, stopStreaming } = useChat();
  const scrollRef = useRef<HTMLDivElement>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    if (messagesEndRef.current) {
      messagesEndRef.current.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages, isStreaming]);

  const hasMessages = messages.length > 0;

  return (
    <div className="flex h-full flex-col bg-background">
      {/* Header */}
      <header className="flex items-center justify-between border-b border-border/50 bg-background/80 backdrop-blur-xl px-4 py-3">
        <div className="flex items-center gap-3">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 via-indigo-600 to-purple-700 shadow-sm">
            <GraduationCap className="h-4 w-4 text-white" />
          </div>
          <div>
            <h2 className="text-sm font-semibold text-foreground leading-tight">
              UTC Chatbot
            </h2>
            <p className="text-[11px] text-muted-foreground">
              Hỗ trợ tuyển sinh ĐH GTVT
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1">
          <ThemeToggle />
        </div>
      </header>

      {/* Messages Area */}
      {hasMessages ? (
        <ScrollArea className="flex-1" ref={scrollRef}>
          <div className="mx-auto max-w-3xl">
            {messages.map((message, index) => (
              <ChatMessage
                key={message.id}
                message={message}
                isStreaming={
                  isStreaming &&
                  index === messages.length - 1 &&
                  message.role === "assistant"
                }
              />
            ))}
            {isStreaming &&
              messages.length > 0 &&
              messages[messages.length - 1].role === "assistant" &&
              messages[messages.length - 1].content === "" && (
                <div className="px-4 py-2 mx-auto max-w-3xl">
                  <div className="flex gap-3">
                    <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 via-indigo-600 to-purple-700 shadow-sm shrink-0 mt-0.5">
                      <GraduationCap className="h-4 w-4 text-white" />
                    </div>
                    <TypingIndicator />
                  </div>
                </div>
              )}
            <div ref={messagesEndRef} className="h-4" />
          </div>
        </ScrollArea>
      ) : (
        <WelcomeScreen onSendMessage={sendMessage} />
      )}

      {/* Input Area */}
      <ChatInput
        onSendMessage={sendMessage}
        onStopStreaming={stopStreaming}
        isStreaming={isStreaming}
      />
    </div>
  );
}
