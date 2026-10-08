# syntax=docker/dockerfile:1
# Images are pinned and were pulled before being written here (spec section 10).

FROM node:24.21.0-alpine3.24 AS build
WORKDIR /web
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.30.5-alpine3.24
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist.nosync /usr/share/nginx/html
EXPOSE 3001
