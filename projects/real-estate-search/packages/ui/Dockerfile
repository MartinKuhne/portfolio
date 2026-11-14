FROM node:22-alpine AS builder

WORKDIR /app

# Copy root workspace manifests and configs
COPY package.json package-lock.json tsconfig.base.json ./
COPY packages/shared/package.json ./packages/shared/
COPY packages/api/package.json ./packages/api/
COPY packages/ui/package.json ./packages/ui/

# Install workspace dependencies
RUN npm ci

# Copy sources for shared and ui packages
COPY packages/shared ./packages/shared
COPY packages/ui ./packages/ui

# Build shared and ui workspaces
RUN npm run build -w @real-estate/shared && npm run build -w @real-estate/ui

FROM nginx:alpine AS runner

COPY --from=builder /app/packages/ui/dist /usr/share/nginx/html
COPY packages/ui/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
