"use client";

import { useState } from "react";
import { ChevronDown, ChevronUp, FileText } from "lucide-react";
import type { Source } from "@/types/chat";

interface SourceCardProps {
  sources: Source[];
}

export function SourceCard({ sources }: SourceCardProps) {
  const [isExpanded, setIsExpanded] = useState(false);

  if (!sources || sources.length === 0) return null;

  return (
    <div className="mt-3 rounded-lg border border-border/50 bg-muted/30 overflow-hidden">
      <button
        onClick={() => setIsExpanded(!isExpanded)}
        className="flex w-full items-center justify-between px-3 py-2 text-xs font-medium text-muted-foreground hover:text-foreground transition-colors"
      >
        <span className="flex items-center gap-1.5">
          <FileText className="h-3.5 w-3.5" />
          {sources.length} nguồn tham khảo
        </span>
        {isExpanded ? (
          <ChevronUp className="h-3.5 w-3.5" />
        ) : (
          <ChevronDown className="h-3.5 w-3.5" />
        )}
      </button>

      {isExpanded && (
        <div className="border-t border-border/50 divide-y divide-border/30">
          {sources.map((source, index) => (
            <div
              key={index}
              className="px-3 py-2.5 animate-in fade-in slide-in-from-top-1 duration-200"
              style={{ animationDelay: `${index * 50}ms` }}
            >
              <div className="flex items-start justify-between gap-2">
                <h4 className="text-xs font-semibold text-foreground leading-tight">
                  {source.title}
                </h4>
                <span className="shrink-0 rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] font-medium text-primary">
                  {Math.round(source.score * 100)}%
                </span>
              </div>
              <p className="mt-1 text-[11px] text-muted-foreground leading-relaxed line-clamp-2">
                {source.content}
              </p>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
