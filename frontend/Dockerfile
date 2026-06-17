FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist/seat-reservation /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
