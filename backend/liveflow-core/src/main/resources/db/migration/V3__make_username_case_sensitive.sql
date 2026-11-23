-- 复用原唯一索引，按注册时的拼写区分账号名。
ALTER TABLE users
    MODIFY COLUMN username VARCHAR(32)
    CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
