# 前端镜像：只装"已经构建好的静态产物"。
# 构建在宿主机执行（npm run build），镜像里不再跑 npm ci——
# 本地迭代快，也避免容器内重新下载依赖。
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html

EXPOSE 80
