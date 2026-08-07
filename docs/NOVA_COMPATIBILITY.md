# Nova 兼容性边界

Nova 当前由 Eta 代码基线演进而来。为保证已安装用户、LSPosed 配置和本地数据可继续使用，本阶段只迁移用户可见品牌与发布基础设施，不直接改动下列持久标识。

## 暂时保留的旧标识

- Android `applicationId` 与 Kotlin `namespace`：`fuck.andes`
- Xposed Java 入口类及包路径
- 已存在的数据库、SharedPreferences、ContentProvider authority 与自定义权限标识
- 与旧版本升级、远程配置和进程间通信相关的内部常量

这些标识不代表最终命名。它们属于兼容性协议的一部分，不能通过全局搜索替换完成迁移。

## 已迁移的内容

- 应用名称与无障碍服务可见名称改为 Nova
- 普通 CI 不再依赖 Release 签名
- 正式签名发布使用独立工作流和 `NOVA_RELEASE_*` Secrets
- Gradle 优先读取 `NOVA_RELEASE_*`，并临时兼容本地 `ETA_RELEASE_*` 环境变量

## 后续包名迁移要求

只有在以下条件全部满足后，才应考虑修改 `applicationId` 或内部包路径：

1. 明确是否保留旧应用覆盖升级能力
2. 完成 Room 数据库、DataStore 与 SharedPreferences 迁移方案
3. 完成 LSPosed 模块作用域和 RemotePreferences 迁移方案
4. 完成 ContentProvider authority、自定义权限和 IPC 调用方校验迁移
5. 提供旧版本备份、回滚和失败恢复路径
6. 通过至少一轮升级安装测试和一轮全新安装测试

在这些条件未满足前，继续保留旧内部标识比直接改名更安全。
