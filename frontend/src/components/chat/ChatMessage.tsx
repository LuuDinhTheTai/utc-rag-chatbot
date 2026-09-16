"use client";

import { useMemo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Copy, Check, GraduationCap, User } from "lucide-react";
import { useState } from "react";
import type { Message } from "@/types/chat";
import { SourceCard } from "./SourceCard";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";

interface ChatMessageProps {
  message: Message;
  isStreaming?: boolean;
}

export function ChatMessage({ message, isStreaming }: ChatMessageProps) {
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const isUser = message.role === "user";

  const handleCopy = async (text: string, id: string) => {
    await navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  const formattedTime = useMemo(() => {
    const date = new Date(message.timestamp);
    return date.toLocaleTimeString("vi-VN", {
      hour: "2-digit",
      minute: "2-digit",
    });
  }, [message.timestamp]);

  return (
    <div
      className={`group flex gap-3 px-4 py-4 animate-in fade-in slide-in-from-bottom-2 duration-300 ${
        isUser ? "flex-row-reverse" : ""
      }`}
    >
      {/* Avatar */}
      <div className="shrink-0 mt-0.5">
        {isUser ? (
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-indigo-600 shadow-sm">
            <User className="h-4 w-4 text-white" />
          </div>
        ) : (
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 via-indigo-600 to-purple-700 shadow-sm shadow-blue-500/20">
            <GraduationCap className="h-4 w-4 text-white" />
          </div>
        )}
      </div>

      {/* Content */}
      <div
        className={`flex max-w-[85%] flex-col sm:max-w-[75%] ${
          isUser ? "items-end" : "items-start"
        }`}
      >
        <div
          className={`relative rounded-2xl px-4 py-3 ${
            isUser
              ? "bg-gradient-to-br from-blue-600 to-indigo-600 text-white shadow-md shadow-blue-500/20"
              : "bg-card border border-border/50 text-foreground shadow-sm"
          }`}
        >
          {isUser ? (
            <p className="whitespace-pre-wrap text-sm leading-relaxed">
              {message.content}
            </p>
          ) : (
            <div className="prose prose-sm dark:prose-invert max-w-none prose-p:leading-relaxed prose-p:my-1 prose-headings:my-2 prose-ul:my-1 prose-ol:my-1 prose-li:my-0.5 prose-table:my-2 prose-pre:my-2 prose-blockquote:my-2 prose-hr:my-3">
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                components={{
                  code({ className, children, ...props }) {
                    const match = /language-(\w+)/.exec(className || "");
                    const codeString = String(children).replace(/\n$/, "");
                    const isInline = !match && !className;

                    if (isInline) {
                      return (
                        <code
                          className="rounded bg-muted px-1.5 py-0.5 text-xs font-mono"
                          {...props}
                        >
                          {children}
                        </code>
                      );
                    }

                    const codeId = `code-${message.id}-${codeString.substring(0, 20)}`;

                    return (
                      <div className="group/code relative my-2 overflow-hidden rounded-lg border border-border/50 bg-muted/50">
                        <div className="flex items-center justify-between border-b border-border/50 bg-muted/80 px-3 py-1.5">
                          <span className="text-[11px] font-medium text-muted-foreground">
                            {match ? match[1] : "code"}
                          </span>
                          <Tooltip>
                            <TooltipTrigger
                              render={
                                <button
                                  className="flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] text-muted-foreground hover:text-foreground transition-colors"
                                />
                              }
                              onClick={() => handleCopy(codeString, codeId)}
                            >
                              {copiedId === codeId ? (
                                <>
                                  <Check className="h-3 w-3 text-green-500" />
                                  <span>Đã sao chép</span>
                                </>
                              ) : (
                                <>
                                  <Copy className="h-3 w-3" />
                                  <span>Sao chép</span>
                                </>
                              )}
                            </TooltipTrigger>
                            <TooltipContent>Sao chép mã</TooltipContent>
                          </Tooltip>
                        </div>
                        <pre className="overflow-x-auto p-3 !my-0 !bg-transparent">
                          <code className={`text-xs ${className || ""}`} {...props}>
                            {children}
                          </code>
                        </pre>
                      </div>
                    );
                  },
                  table({ children }) {
                    return (
                      <div className="my-2 overflow-x-auto rounded-lg border border-border/50">
                        <table className="w-full !my-0">{children}</table>
                      </div>
                    );
                  },
                  th({ children }) {
                    return (
                      <th className="bg-muted/50 px-3 py-2 text-left text-xs font-semibold">
                        {children}
                      </th>
                    );
                  },
                  td({ children }) {
                    return (
                      <td className="px-3 py-2 text-xs">{children}</td>
                    );
                  },
                }}
              >
                {message.content}
              </ReactMarkdown>
              {isStreaming && message.content && (
                <span className="inline-block w-2 h-4 bg-primary/60 animate-pulse ml-0.5 align-middle rounded-sm" />
              )}
            </div>
          )}

          {/* Copy button for messages */}
          {!isUser && !isStreaming && message.content && (
            <Tooltip>
              <TooltipTrigger
                render={
                  <button
                    className="absolute -bottom-1 right-2 translate-y-full opacity-0 group-hover:opacity-100 flex items-center gap-1 rounded-md bg-muted/80 px-2 py-1 text-[11px] text-muted-foreground hover:text-foreground transition-all border border-border/50 shadow-sm"
                  />
                }
                onClick={() => handleCopy(message.content, `msg-${message.id}`)}
              >
                {copiedId === `msg-${message.id}` ? (
                  <Check className="h-3 w-3 text-green-500" />
                ) : (
                  <Copy className="h-3 w-3" />
                )}
              </TooltipTrigger>
              <TooltipContent>Sao chép tin nhắn</TooltipContent>
            </Tooltip>
          )}
        </div>

        {/* Sources */}
        {!isUser && message.sources && message.sources.length > 0 && (
          <div className="w-full mt-1">
            <SourceCard sources={message.sources} />
          </div>
        )}

        {/* Timestamp */}
        <span className="mt-1 text-[10px] text-muted-foreground/50 px-1 opacity-0 group-hover:opacity-100 transition-opacity">
          {formattedTime}
        </span>
      </div>
    </div>
  );
}
