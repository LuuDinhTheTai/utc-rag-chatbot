export interface Source {
  title: string;
  content: string;
  score: number;
}

export interface Message {
  id: string;
  role: "user" | "assistant";
  content: string;
  sources?: Source[];
  timestamp: Date;
}

export interface Conversation {
  id: string;
  title: string;
  messages: Message[];
  createdAt: Date;
  updatedAt: Date;
}

export interface SSEChunk {
  type: "content" | "sources" | "done" | "error";
  data: string;
  sources?: Source[];
}
