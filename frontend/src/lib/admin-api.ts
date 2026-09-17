import { supabase } from "./supabase";

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8000";

async function getAuthHeader() {
  const { data: { session } } = await supabase.auth.getSession();
  if (!session) {
    throw new Error("No active session found");
  }
  return {
    Authorization: `Bearer ${session.access_token}`,
  };
}

export async function fetchDocuments() {
  const headers = await getAuthHeader();
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/documents`, {
    method: "GET",
    headers,
  });
  
  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.detail || "Failed to fetch documents");
  }
  
  const result = await response.json();
  return result.data.documents;
}

export async function uploadDocument(file: File) {
  const headers = await getAuthHeader();
  
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch(`${API_BASE_URL}/api/v1/admin/documents`, {
    method: "POST",
    headers, // Don't set Content-Type, fetch will set it automatically with the boundary
    body: formData,
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.detail || "Failed to upload document");
  }

  const result = await response.json();
  return result.data;
}

export async function deleteDocument(documentId: string) {
  const headers = await getAuthHeader();
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/documents/${documentId}`, {
    method: "DELETE",
    headers,
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.detail || "Failed to delete document");
  }

  return true;
}

export async function reindexDocument(documentId: string) {
  const headers = await getAuthHeader();
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/documents/${documentId}/reindex`, {
    method: "POST",
    headers,
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.detail || "Failed to reindex document");
  }

  return true;
}
