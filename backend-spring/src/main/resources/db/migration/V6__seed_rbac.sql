-- ============================================================================
-- WIN-25 (v3.3.0): RBAC 种子 + 既有 users.role 回填（Flyway V6，幂等）
--
-- 来源：docs/DDL-win25-account-pool-rbac-v1.0.sql（架构终稿，WIN-27 评审通过）
-- 设计文档：docs/ARCH-win25-account-pool-rbac-v1.0.md（Part C.2 / 附录 A）
-- ============================================================================

-- 内置角色（固定 UUID，不可重复创建，A12/A15）
INSERT INTO roles (id, code, name, builtin) VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin', '管理员', TRUE),
    ('00000000-0000-0000-0000-000000000002', 'user',  '普通用户', TRUE)
ON CONFLICT (code) DO NOTHING;

-- 权限码种子（13 个，对齐 PRD §8.1 + 管理控制台 IA；api_path 为后端鉴权映射/前端清单来源）
INSERT INTO permissions (code, type, parent_code, label, api_path, sort_order) VALUES
    ('workbench.view',        'menu',   NULL,               '生图工作台',   NULL,                           10),
    ('usage.me',              'menu',   NULL,               '我的用量',     '/api/nova/usage/me',          20),
    ('admin.console.view',    'menu',   NULL,               '管理控制台',   NULL,                           30),
    ('project.manage',        'menu',   'admin.console.view','项目管理',    '/api/nova/projects',          31),
    ('asset.manage',          'menu',   'admin.console.view','素材管理',    '/api/nova/assets',            32),
    ('user.manage',           'menu',   'admin.console.view','用户管理',    '/api/nova/admin/users',       33),
    ('account.manage',        'menu',   'admin.console.view','账号池管理',  '/api/nova/admin/accounts',    34),
    ('account.test',          'button', 'account.manage',    '测试连通',    '/api/nova/admin/accounts/*/test', 341),
    ('model.catalog.manage',  'menu',   'account.manage',    '模型目录',    '/api/nova/admin/models',      35),
    ('pricing.manage',        'menu',   'account.manage',    '价格配置',    '/api/nova/admin/pricing',     36),
    ('audit.view',            'menu',   'admin.console.view','审计与费用',  '/api/nova/admin/usage',       37),
    ('audit.export',          'button', 'audit.view',        '导出 CSV',    '/api/nova/admin/usage/export', 371),
    ('rbac.manage',           'menu',   'admin.console.view','角色与权限',  '/api/nova/admin/roles',       38)
ON CONFLICT (code) DO NOTHING;

-- role_permissions：admin = 全部权限；user = 工作台默认集（workbench.view + usage.me）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code = 'admin'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p ON p.code IN ('workbench.view', 'usage.me')
WHERE r.code = 'user'
ON CONFLICT DO NOTHING;

-- 回填既有 users.role → user_roles（R4：users.role 保留为主角色快捷字段，
-- 权限判定以 user_roles→role_permissions 为准；admin→admin 角色，user→user 角色）
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u JOIN roles r ON r.code = u.role
ON CONFLICT DO NOTHING;
