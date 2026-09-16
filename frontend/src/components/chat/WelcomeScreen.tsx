"use client";

import { GraduationCap, Sparkles } from "lucide-react";

interface WelcomeScreenProps {
  onSendMessage: (message: string) => void;
}

const suggestedQuestions = [
  {
    icon: "🎯",
    text: "Điểm chuẩn các ngành năm 2025 là bao nhiêu?",
  },
  {
    icon: "🏫",
    text: "Trường có những ngành đào tạo nào?",
  },
  {
    icon: "💰",
    text: "Học phí các ngành như thế nào?",
  },
  {
    icon: "📋",
    text: "Điều kiện xét tuyển đại học là gì?",
  },
];

export function WelcomeScreen({ onSendMessage }: WelcomeScreenProps) {
  return (
    <div className="flex flex-1 flex-col items-center justify-center px-4 py-8 animate-in fade-in duration-500">
      {/* Logo & Title */}
      <div className="mb-8 flex flex-col items-center text-center">
        <div className="relative mb-6">
          <div className="flex h-20 w-20 items-center justify-center rounded-2xl bg-gradient-to-br from-blue-600 via-indigo-600 to-purple-700 shadow-lg shadow-blue-500/25">
            <GraduationCap className="h-10 w-10 text-white" />
          </div>
          <div className="absolute -right-1 -top-1 flex h-7 w-7 items-center justify-center rounded-full bg-gradient-to-r from-amber-400 to-orange-500 shadow-md">
            <Sparkles className="h-3.5 w-3.5 text-white" />
          </div>
        </div>
        <h1 className="mb-2 text-2xl font-bold tracking-tight text-foreground sm:text-3xl">
          UTC Chatbot
        </h1>
        <p className="max-w-md text-sm text-muted-foreground sm:text-base">
          Trợ lý AI hỗ trợ tuyển sinh{" "}
          <span className="font-semibold text-foreground">
            Trường Đại học Giao thông Vận tải
          </span>
        </p>
      </div>

      {/* Suggested Questions */}
      <div className="grid w-full max-w-2xl gap-3 sm:grid-cols-2">
        {suggestedQuestions.map((question, index) => (
          <button
            key={index}
            onClick={() => onSendMessage(question.text)}
            className="group flex items-start gap-3 rounded-xl border border-border/50 bg-card/50 p-4 text-left transition-all duration-200 hover:border-primary/30 hover:bg-accent/50 hover:shadow-md hover:shadow-primary/5 active:scale-[0.98]"
            style={{ animationDelay: `${index * 100}ms` }}
          >
            <span className="text-xl leading-none">{question.icon}</span>
            <span className="text-sm text-muted-foreground transition-colors group-hover:text-foreground">
              {question.text}
            </span>
          </button>
        ))}
      </div>

      {/* Footer */}
      <p className="mt-8 text-xs text-muted-foreground/60">
        Powered by AI • Dữ liệu tuyển sinh UTC
      </p>
    </div>
  );
}
