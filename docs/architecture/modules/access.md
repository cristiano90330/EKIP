# 身份与知识库权限

前端会话经 Java 认证和授权，业务入口据此决定资源访问范围。

个人账号页通过 `GET /user/me` 读取当前登录者的用户 ID、用户名和部门。切换账号沿用登出和登录流程。登录页的「管理员登录」在建立浏览器会话前校验 `/user/account-admin/access`，使用部署配置 `EKIP_ACCOUNT_ADMIN_USERNAME` 指定的账号。校验通过后进入由 `/user/account-admin` 提供的账号与部门管理。

## 源码与契约入口

- 身份入口与安全配置 [UserController.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/controller/UserController.java)、[security/](../../../knowledge-platform/src/main/java/com/pony/knowledge/security/)。
- 知识库授权与成员管理 [KnowledgeBaseAccessService.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/KnowledgeBaseAccessService.java)、[KnowledgeBaseMemberServiceImpl.java](../../../knowledge-platform/src/main/java/com/pony/knowledge/service/impl/KnowledgeBaseMemberServiceImpl.java)。
- 前端认证状态与权限矩阵 [auth.ts](../../../frontend/src/stores/auth.ts)、[access-matrix.md](../contracts/access-matrix.md)。
