# Usque

基于 Cloudflare MASQUE 协议 (RFC 9484 Connect-IP) 的 Android 客户端。

> 原项目 [Diniboy1123/usque](https://github.com/Diniboy1123/usque) 的 Android 移植版，使用 gomobile 将 Go 核心编译为 AAR，Kotlin 构建 Android VPN 界面。

## 功能

- 🚀 Cloudflare MASQUE 隧道 (Connect-IP over QUIC)
- 📱 Android VPN 服务，系统级全局代理
- 🔑 自动注册 WARP 账号 / Zero Trust JWT 登录
- ♻️ 支持预置凭证（许可证 + Token + Account ID）
- ⚙️ 全中文淡色 UI，首页大连接按钮 + 脉冲动画
- 🌍 出口 IP 归属地国旗显示 (ors.de5.net/ip)
- ⏱ 实时延迟检测 / 网速显示
- 📋 DNS 测试 / 配置导出 / 预设管理
- 🔔 通知栏常驻网速

## 下载

从 [Actions](https://github.com/obrige/usque-android/actions) 页面下载最新 `usque-vpn-debug` Artifact。

## 构建

```bash
# 1. 安装 gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init

# 2. 编译 AAR
gomobile bind -v -target=android/arm64,android/arm -androidapi 24 \
    -ldflags="-s -w" -o android/usque-vpn/app/libs/usque.aar \
    github.com/obrige/usque-android/android

# 3. 编译 APK
cd android/usque-vpn
./gradlew assembleDebug
```

## 设置说明

| 配置项 | 说明 |
|--------|------|
| SNI | TLS SNI，默认 `cdnjs.cloudflare.com` |
| 端点 | IPv4/IPv6 MASQUE 端点 |
| DNS | 自定义 DNS 服务器，默认 Google + Quad9 |
| JWT | Cloudflare Zero Trust Token |
| 许可证/Token/ID | 预置凭证，三要素齐全则跳过新注册 |
| 私钥 | 客户端 ECDSA 私钥 (Base64)，留空自动生成 |
| 端点公钥 | 对端公钥 (PEM)，留空自动获取 |

## 许可

MIT
