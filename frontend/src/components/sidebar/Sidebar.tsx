"use client";

import {
  GraduationCap,
  Plus,
  Trash2,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useChatStore } from "@/stores/chat-store";
import { ConversationItem } from "./ConversationItem";
import type { Conversation } from "@/types/chat";

interface SidebarProps {
  onClose?: () => void;
}

export function Sidebar({ onClose }: SidebarProps) {
  const {
    conversations,
    activeConversationId,
    createConversation,
    deleteConversation,
    renameConversation,
    setActiveConversation,
    clearAllConversations,
  } = useChatStore();

  const handleNewChat = () => {
    createConversation();
    onClose?.();
  };

  const handleSelectConversation = (id: string) => {
    setActiveConversation(id);
    onClose?.();
  };

  // Group conversations by date
  const groupedConversations = groupByDate(conversations);

  return (
    <div className="flex h-full flex-col bg-sidebar">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-4">
        <div className="flex items-center gap-2.5">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-600 via-indigo-600 to-purple-700 shadow-sm">
            <GraduationCap className="h-4 w-4 text-white" />
          </div>
          <div>
            <h1 className="text-sm font-bold text-foreground leading-tight">
              UTC
            </h1>
            <p className="text-[10px] text-muted-foreground">Tuyển sinh</p>
          </div>
        </div>
      </div>

      {/* New Chat Button */}
      <div className="px-3 pb-2">
        <Button
          onClick={handleNewChat}
          variant="outline"
          className="w-full justify-start gap-2 rounded-lg border-dashed border-border/50 bg-transparent text-xs font-medium text-muted-foreground hover:border-primary/30 hover:text-foreground hover:bg-accent/50 transition-all"
        >
          <Plus className="h-4 w-4" />
          Cuộc trò chuyện mới
        </Button>
      </div>

      <Separator className="opacity-50" />

      {/* Conversations List */}
      <ScrollArea className="flex-1 px-2 py-2">
        {conversations.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-12 text-center">
            <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-muted/50">
              <GraduationCap className="h-6 w-6 text-muted-foreground/40" />
            </div>
            <p className="text-xs text-muted-foreground/60">
              Chưa có cuộc trò chuyện nào
            </p>
            <p className="mt-1 text-[10px] text-muted-foreground/40">
              Bắt đầu bằng cách đặt câu hỏi
            </p>
          </div>
        ) : (
          <div className="space-y-1">
            {Object.entries(groupedConversations).map(([label, convos]) => (
              <div key={label}>
                <p className="px-3 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground/50">
                  {label}
                </p>
                {convos.map((conversation) => (
                  <ConversationItem
                    key={conversation.id}
                    conversation={conversation}
                    isActive={conversation.id === activeConversationId}
                    onClick={() =>
                      handleSelectConversation(conversation.id)
                    }
                    onRename={renameConversation}
                    onDelete={deleteConversation}
                  />
                ))}
              </div>
            ))}
          </div>
        )}
      </ScrollArea>

      {/* Footer */}
      {conversations.length > 0 && (
        <>
          <Separator className="opacity-50" />
          <div className="px-3 py-3">
            <Tooltip>
              <TooltipTrigger
                render={
                  <Button
                    variant="ghost"
                    size="sm"
                    className="w-full justify-start gap-2 text-xs text-muted-foreground hover:text-destructive transition-colors"
                  />
                }
                onClick={clearAllConversations}
              >
                <Trash2 className="h-3.5 w-3.5" />
                Xóa tất cả
              </TooltipTrigger>
              <TooltipContent>Xóa tất cả cuộc trò chuyện</TooltipContent>
            </Tooltip>
          </div>
        </>
      )}
    </div>
  );
}

function groupByDate(
  conversations: Conversation[]
): Record<string, Conversation[]> {
  const groups: Record<string, Conversation[]> = {};
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today.getTime() - 86400000);
  const lastWeek = new Date(today.getTime() - 7 * 86400000);

  for (const conversation of conversations) {
    const date = new Date(conversation.updatedAt);
    let label: string;

    if (date >= today) {
      label = "Hôm nay";
    } else if (date >= yesterday) {
      label = "Hôm qua";
    } else if (date >= lastWeek) {
      label = "7 ngày trước";
    } else {
      label = "Cũ hơn";
    }

    if (!groups[label]) groups[label] = [];
    groups[label].push(conversation);
  }

  return groups;
}
