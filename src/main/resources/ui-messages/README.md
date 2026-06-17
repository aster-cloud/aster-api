# ui-messages（界面文案，aster-api 自带副本）

这些 `<locale-id>.json` 是统一语言包的**界面文案**（ADR 0018），由 `UiMessagesService`
启动时从 classpath 加载，`/api/v1/messages/{locale}` 端点直接吐内存。

## 为什么 api 自带，而不是从语言包 jar 读

ADR 0018 规定界面文案走**独立 npm 通道、不进语言包 JVM jar**（避免全生态版本级联）。
此外，`cloud.aster-lang:aster-lang-{en,zh,de}` 这三个 Maven 包归属于历史的
`aster-lang-en/zh/de` 仓，aster-lang-locales 无法跨仓发布同名包（GitHub Packages 422），
所以这些 jar 也根本不会携带 `ui-messages/` 资源。

因此 aster-api **自带一份 ui-messages 副本**（同 aster-cloud 前端的内嵌兜底模式）：
- `runtimeOnly en/zh/de/hi` 这些语言包 jar 仍保留，但只提供 **lexicon SPI**（关键词/编译），
  不再用于 messages。
- messages 的真相源是 `@aster-cloud/ui-messages`(+`-hi`) npm 包 / `aster-lang-locales`
  仓的 `locales/<lang>/src/main/resources/ui-messages/`。

## 同步

改文案 = 改真相源（locales 仓）→ 同步到这里：

```bash
cp ../../../../../aster-lang-locales/locales/en/src/main/resources/ui-messages/en-US.json en-US.json
cp ../../../../../aster-lang-locales/locales/zh/src/main/resources/ui-messages/zh-CN.json zh-CN.json
cp ../../../../../aster-lang-locales/locales/de/src/main/resources/ui-messages/de-DE.json de-DE.json
cp ../../../../../aster-lang-hi/src/main/resources/ui-messages/hi-IN.json hi-IN.json
```

> 运行时热刷新（Redis pub/sub `aster.i18n.messages.reload`）只会重载 classpath 上已有的
> 资源；新增/改文案需重新同步 + 重新部署（或后续接 build-time 从 npm 拉取自动化）。
