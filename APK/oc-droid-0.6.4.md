Release v0.6.4

### Features

- 连续 tool 调用自动折叠成 FoldBar
- API26 -legacy p12 instrumented 测试 + UX polish
- mTLS 客户端证书接入 + 模型供应商开关

### Bug Fixes

- openSessionFromDeepLink flaky 测试确定性修复
- instrumented 死断言修复 + 单 key 断言收紧

### Miscellaneous

- minSdk 26→34（移除老 Android BC PKCS12 兼容顾虑，API26 硬门不再需要）

