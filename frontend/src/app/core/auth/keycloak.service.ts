import { Injectable } from '@angular/core';
import Keycloak from 'keycloak-js';

@Injectable({ providedIn: 'root' })
export class KeycloakService {
  private keycloak = new Keycloak({
    url: 'http://localhost:8180',
    realm: 'seat-reservation',
    clientId: 'seat-reservation-app'
  });
  
  async init(): Promise<void> {
    await this.keycloak.init({ onLoad: 'login-required', checkLoginIframe: false });
  }
  
  getToken(): string { return this.keycloak.token ?? ''; }
  logout(): void { this.keycloak.logout({ redirectUri: 'http://localhost:4200' }); }
  hasRole(role: string): boolean { return this.keycloak.hasRealmRole(role); }
}
