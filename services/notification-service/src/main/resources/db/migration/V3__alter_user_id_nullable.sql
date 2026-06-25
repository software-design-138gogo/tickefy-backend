-- Bỏ NOT NULL constraint của user_id trong bảng notifications để hỗ trợ system-wide broadcast notifications (userId = null)
ALTER TABLE notifications ALTER COLUMN user_id DROP NOT NULL;
