FROM node:22-alpine

WORKDIR /app

# Copy root workspace manifests and configs
COPY package.json package-lock.json tsconfig.base.json sample_listings.json ./
COPY packages/shared/package.json ./packages/shared/
COPY packages/api/package.json ./packages/api/
COPY packages/ui/package.json ./packages/ui/

# Install workspace dependencies
RUN npm ci

# Copy sources for shared and api packages
COPY packages/shared ./packages/shared
COPY packages/api ./packages/api

# Build shared and api workspaces
RUN npm run build -w @real-estate/shared && npm run build -w @real-estate/api

ENV PORT=4000
EXPOSE 4000

CMD ["node", "packages/api/dist/index.js"]
