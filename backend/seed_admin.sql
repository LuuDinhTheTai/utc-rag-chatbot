-- 1. Tạo tài khoản trong auth.users với email và mật khẩu (đã mã hóa)
WITH new_admin AS (
  INSERT INTO auth.users (
    instance_id,
    id,
    aud,
    role,
    email,
    encrypted_password,
    email_confirmed_at,
    recovery_sent_at,
    last_sign_in_at,
    raw_app_meta_data,
    raw_user_meta_data,
    created_at,
    updated_at,
    confirmation_token,
    email_change,
    email_change_token_new,
    recovery_token
  )
  VALUES (
    '00000000-0000-0000-0000-000000000000',
    gen_random_uuid(),
    'authenticated',
    'authenticated',
    'admin@utc.edu.vn',
    crypt('adminpassword123', gen_salt('bf')),
    now(),
    now(),
    now(),
    '{"provider":"email","providers":["email"]}',
    '{"full_name": "UTC Admin"}',
    now(),
    now(),
    '',
    '',
    '',
    ''
  )
  RETURNING id
)
-- 2. Cập nhật role thành 'admin' trong bảng public.profiles (trigger đã tạo trước đó)
UPDATE public.profiles
SET role = 'admin'
WHERE id = (SELECT id FROM new_admin);
