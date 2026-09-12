# Infinia 项目介绍页已合并

介绍页源码已迁移至 `store-web/src/intro`，不再是独立 Next.js workspace。

- 开发：仓库根目录运行 `yarn web`，访问 http://localhost:8089/。
- 商店：http://localhost:8089/store。
- 构建：`yarn web:build`，两者共用 `store-web/dist`。
- `yarn website` / `yarn website:build` 保留为兼容别名，不再启动第二个服务。
- 原有 `website/out`、`.next`、`node_modules` 如仍存在，仅为历史构建缓存，不参与构建与部署。
