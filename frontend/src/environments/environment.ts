export const environment = {
  production: false,
  apiUrl: '',  // Use relative URLs - nginx proxies /api to backend
  keycloak: {
    url: 'http://localhost:8180',
    realm: 'seat-reservation',
    clientId: 'seat-reservation-app'
  }
};
