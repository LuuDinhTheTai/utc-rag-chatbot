"use client";

import { useState, useRef, useCallback, useEffect } from "react";
import { Send, Square } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";

interface ChatInputProps {
  onSendMessage: (message: string) => void;
  onStopStreaming: () => void;
  isStreaming: boolean;
  disabled?: boolean;
}

export function ChatInput({
  onSendMessage,
  onStopStreaming,
  isStreaming,
  disabled,
}: ChatInputProps) {
  const [input, setInput] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const adjustHeight = useCallback(() => {
    const textarea = textareaRef.current;
    if (textarea) {
      textarea.style.height = "auto";
      const newHeight = Math.min(textarea.scrollHeight, 200);
      textarea.style.height = `${newHeight}px`;
    }
  }, []);

  useEffect(() => {
    adjustHeight();
  }, [input, adjustHeight]);

  const handleSubmit = useCallback(() => {
    if (input.trim() && !isStreaming && !disabled) {
      onSendMessage(input.trim());
      setInput("");
      if (textareaRef.current) {
        textareaRef.current.style.height = "auto";
      }
    }
  }, [input, isStreaming, disabled, onSendMessage]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit]
  );

  return (
    <div className="border-t border-border/50 bg-background/80 backdrop-blur-xl px-4 py-3">
      <div className="mx-auto max-w-3xl">
        <div className="relative flex items-end gap-2 rounded-2xl border border-border/50 bg-card/80 px-3 py-2 shadow-sm transition-all focus-within:border-primary/30 focus-within:shadow-md focus-within:shadow-primary/5">
          <textarea
            ref={textareaRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Nhập câu hỏi về tuyển sinh UTC..."
            rows={1}
            disabled={disabled}
            className="flex-1 resize-none bg-transparent text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none disabled:opacity-50 py-1.5 max-h-[200px]"
          />

          {isStreaming ? (
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0 rounded-xl bg-destructive/10 text-destructive hover:bg-destructive/20 transition-colors"
                  />
                }
                onClick={onStopStreaming}
              >
                <Square className="h-3.5 w-3.5 fill-current" />
              </TooltipTrigger>
              <TooltipContent>Dừng trả lời</TooltipContent>
            </Tooltip>
          ) : (
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    size="icon"
                    disabled={!input.trim() || disabled}
                    className="h-8 w-8 shrink-0 rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 text-white shadow-sm hover:shadow-md hover:shadow-blue-500/25 transition-all disabled:opacity-30 disabled:shadow-none"
                  />
                }
                onClick={handleSubmit}
              >
                <Send className="h-3.5 w-3.5" />
              </TooltipTrigger>
              <TooltipContent>
                Gửi tin nhắn (Enter)
              </TooltipContent>
            </Tooltip>
          )}
        </div>
        <p className="mt-2 text-center text-[10px] text-muted-foreground/40">
          Nhấn Enter để gửi • Shift + Enter để xuống dòng
        </p>
      </div>
    </div>
  );
}
