-- Infinia Store Platform — admin-configured remote database endpoints
-- (远程数据库配置). Credentials are AES-GCM sealed in password_cipher; exactly
-- one enabled row is exported to the data-source override file on activation
-- and applied to spring.datasource.* on the next restart.
CREATE TABLE remote_database (
    id                UUID PRIMARY KEY,
    name              VARCHAR(100) NOT NULL,
    jdbc_url          VARCHAR(500) NOT NULL,
    username          VARCHAR(200) NOT NULL,
    password_cipher   VARCHAR(2000) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT FALSE,
    last_tested_at    TIMESTAMP(6) WITH TIME ZONE,
    last_test_ok      BOOLEAN,
    last_test_error   VARCHAR(1000),
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
