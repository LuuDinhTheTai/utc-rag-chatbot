"use client";

import { useState, useRef } from "react";
import { uploadDocument } from "@/lib/admin-api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Upload, X } from "lucide-react";
import { toast } from "@/components/ui/toast";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";

export function DocumentUploadModal({ onUploadSuccess }: { onUploadSuccess: () => void }) {
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      setFile(e.target.files[0]);
    }
  };

  const handleUpload = async () => {
    if (!file) return;

    try {
      setUploading(true);
      await uploadDocument(file);
      toast.add({ title: "Document uploaded successfully" });
      setOpen(false);
      setFile(null);
      onUploadSuccess();
    } catch (err: any) {
      toast.add({
        title: "Upload failed",
        description: err.message,
      });
    } finally {
      setUploading(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button>
          <Upload className="w-4 h-4 mr-2" />
          Upload Document
        </Button>
      </DialogTrigger>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Upload Document</DialogTitle>
          <DialogDescription>
            Supported formats: PDF, TXT, MD, DOCX. Max size: 20MB.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4 py-4">
          {!file ? (
            <div 
              className="border-2 border-dashed rounded-lg p-10 flex flex-col items-center justify-center text-center cursor-pointer hover:bg-muted/50 transition-colors"
              onClick={() => fileInputRef.current?.click()}
            >
              <Upload className="w-8 h-8 text-muted-foreground mb-4" />
              <p className="text-sm font-medium">Click to select a file</p>
              <p className="text-xs text-muted-foreground mt-1">or drag and drop</p>
              <input 
                type="file" 
                className="hidden" 
                ref={fileInputRef}
                onChange={handleFileChange}
                accept=".pdf,.txt,.md,.docx,application/pdf,text/plain,text/markdown,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
              />
            </div>
          ) : (
            <div className="flex items-center justify-between p-4 border rounded-lg bg-muted/30">
              <div className="flex flex-col truncate">
                <span className="font-medium text-sm truncate">{file.name}</span>
                <span className="text-xs text-muted-foreground">{(file.size / 1024 / 1024).toFixed(2)} MB</span>
              </div>
              <Button variant="ghost" size="icon" onClick={() => setFile(null)} disabled={uploading}>
                <X className="w-4 h-4" />
              </Button>
            </div>
          )}
        </div>
        <div className="flex justify-end gap-3">
          <Button variant="outline" onClick={() => setOpen(false)} disabled={uploading}>
            Cancel
          </Button>
          <Button onClick={handleUpload} disabled={!file || uploading}>
            {uploading ? "Uploading & Processing..." : "Upload"}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
