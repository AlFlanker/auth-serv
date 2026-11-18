-- V1: начальная схема identity-service (локальные пользователи, роли, реестр отозванных токенов)

-- Стандартная схема Spring Security для локальных пользователей
CREATE TABLE users (
    username VARCHAR(50)  NOT NULL PRIMARY KEY,
    password VARCHAR(200) NOT NULL,
    enabled  BOOLEAN      NOT NULL
);

CREATE TABLE authorities (
    username  VARCHAR(50) NOT NULL,
    authority VARCHAR(50) NOT NULL,
    CONSTRAINT fk_authorities_users FOREIGN KEY (username) REFERENCES users (username)
);
CREATE UNIQUE INDEX ix_auth_username ON authorities (username, authority);

-- Локальные роли портала (аналог RoleManagementService/user-role-storage монолита)
CREATE TABLE roles (
    id   VARCHAR(50) PRIMARY KEY,
    name VARCHAR(200)
);

-- Реестр отозванных токенов (jti)
CREATE TABLE token_registry (
    jti        VARCHAR(64)              PRIMARY KEY,
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL
);
