-- Существующие названия, в том числе изменённые оператором, сохраняются.
INSERT INTO roles (id, name) VALUES
    ('ROLE_ADMIN', 'Администратор'),
    ('ROLE_USER', 'Пользователь'),
    ('ROLE_EDITOR', 'Редактор')
ON CONFLICT DO NOTHING;
