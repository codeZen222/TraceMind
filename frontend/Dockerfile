# ── 构建阶段：Node 22 + pnpm，产出静态 dist ──
FROM node:22-alpine AS build
WORKDIR /app

# 用 corepack 启用项目锁定的 pnpm 版本
RUN corepack enable

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

# ── 运行阶段：Nginx 托管静态文件 + 反代后端 ──
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
