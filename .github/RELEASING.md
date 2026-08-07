# Nova 发布流程

Nova 将普通构建、代码验证和正式签名发布拆成三套工作流，避免没有发布证书时阻断 Debug 构建与测试。

## Debug 构建

`Nova Android Build` 会在以下情况运行：

- 向 `main` 推送提交
- 创建或更新 Pull Request
- 在 Actions 页面手动运行

该工作流只执行 `:app:assembleDebug`，并上传 `nova-debug-apk` Artifact，保留 14 天。它不读取发布证书，也不会生成可正式分发的 Release APK。

## 单元测试与 Lint

`Nova Verification` 会在以下情况运行：

- 向 `main` 推送提交
- 创建或更新面向 `main` 的 Pull Request
- 在 Actions 页面手动运行

该工作流执行：

1. `:app:testDebugUnitTest`
2. `:app:lintDebug`
3. 无论成功或失败都尽量上传测试与 Lint 报告，保留 14 天

## 配置签名 Secrets

发布证书和密码不得提交到 Git。建议先在仓库中创建名为 `release` 的 GitHub Environment，并在该 Environment 中添加：

- `NOVA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `NOVA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `NOVA_RELEASE_KEY_ALIAS`：Key alias
- `NOVA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/Nova-release.jks | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/Nova-release.jks | gh secret set NOVA_RELEASE_KEYSTORE_BASE64 --env release
gh secret set NOVA_RELEASE_STORE_PASSWORD --env release
gh secret set NOVA_RELEASE_KEY_ALIAS --env release
gh secret set NOVA_RELEASE_KEY_PASSWORD --env release
```

Gradle 优先读取 `NOVA_RELEASE_*` 环境变量，同时临时兼容旧的 `ETA_RELEASE_*` 本地变量。GitHub Actions 的正式发布工作流只使用新的 `NOVA_RELEASE_*` Secrets。

## 构建签名 Release APK

`Nova Signed Release` 仅在以下情况运行：

- 推送 `v*` 标签
- 在 Actions 页面手动运行

工作流会执行 Release 单元测试、构建签名 APK、使用 `apksigner` 验证签名，并上传 APK 与 SHA-256 校验文件。Artifact 保留 30 天。

正式发布前先更新 `versionCode` 和 `versionName`，然后创建与 `versionName` 对应的标签。例如发布 `2.5.1`：

```bash
git tag v2.5.1
git push origin v2.5.1
```

标签推送后，等待 `Nova Signed Release` 完成，然后：

1. 从该次工作流的 Artifacts 下载 `Nova-v2.5.1.apk` 与对应的 `.sha256` 文件。
2. 本地复核 SHA-256 与 APK 签名证书。
3. 在仓库的 `Releases > Draft a new release` 中选择已有标签。
4. 填写 Release Notes 并上传 APK 与校验文件。
5. 检查版本、说明和附件后，由维护者手动发布。

工作流不会自动创建或发布 GitHub Release。
