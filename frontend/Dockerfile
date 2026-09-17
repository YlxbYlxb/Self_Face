# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /build

# 国内服务器直连 npm 官方源很慢，用 build arg 换成 npmmirror：
#   docker compose build --build-arg NPM_REGISTRY=https://registry.npmmirror.com
ARG NPM_REGISTRY=https://registry.npmjs.org

# 备案号在构建期内联进产物，改了必须重新构建才生效。
# 留空则页面底部不渲染备案信息（本地开发和备案审核期间都走这个分支）。
ARG VITE_ICP_BEIAN=
ENV VITE_ICP_BEIAN=$VITE_ICP_BEIAN

# 先装依赖，package-lock.json 没变时复用缓存层
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund --registry="$NPM_REGISTRY"

COPY . .
RUN npm run build

# ---------- 运行阶段 ----------
FROM nginx:1.27-alpine

COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
