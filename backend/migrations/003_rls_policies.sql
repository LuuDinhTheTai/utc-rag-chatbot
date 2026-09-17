-- Enable RLS
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_chunks ENABLE ROW LEVEL SECURITY;

-- Profiles: users can read own profile
CREATE POLICY "Users can view own profile"
    ON profiles FOR SELECT USING (auth.uid() = id);

-- Documents: admins can manage
CREATE POLICY "Admins can manage documents"
    ON documents FOR ALL USING (
        EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND role = 'admin')
    );

-- Chunks: read access for authenticated + service role
-- Since chat is public and doesn't require auth, we need to allow read access
-- Or the RAG service uses service_role key to bypass RLS.
-- Since our FastAPI backend uses service_role_key for vector searches, we don't strictly need public read policy here.
CREATE POLICY "Service role full access to chunks"
    ON document_chunks FOR ALL USING (true);
