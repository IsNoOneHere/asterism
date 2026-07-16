FROM node:20-alpine AS build
WORKDIR /src
COPY package*.json ./
# 构建阶段必须安装 TypeScript/Vite 等开发依赖，避免宿主 npm 配置污染镜像。
RUN npm ci --include=dev
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
ENV V5_CONTROL_PLANE_HOST=control-plane
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
