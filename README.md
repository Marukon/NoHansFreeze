# NoHansFreeze

ColorOS `OplusHansManager` 墓碑白名单模块（基于 LibXposed API 102）。

- **仓库地址**: https://github.com/Marukon/NoHansFreeze
- **包名**: `io.github.marukon.nohansfreeze`
- **默认作用域**: `system` / `android`（系统框架）

## 功能特性

- 通过 Hook `OplusHansImportance.isImportantPkg`、`HansCGroup.hansFreezeLocked` 与 `OplusHansRestriction.isBlockedForStrictMode`，将勾选的应用彻底排除在 ColorOS 后台墓碑冻结及 `DestroyAllSocket` 断网之外。
- 基于 `RemotePreferences` 同步配置，**首次启用模块重启一次手机后，后续增删白名单应用均实时生效**。
- 自动识别已安装的 Xposed / LibXposed 模块并标注 `Xposed` 徽标，支持**一键放行所有 Xposed 模块**。

## 使用步骤

1. 安装 APK 并在 LSPosed 中启用 `NoHansFreeze`，确保作用域已勾选 **系统框架 (`android`)**。
2. **重启手机**，使 `system_server` 加载 Hook。
3. 打开 `NoHansFreeze`，选择需要解除限制的应用或点击一键放行所有 Xposed 模块。
