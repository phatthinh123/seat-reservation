import { Injectable } from '@angular/core';
import Keycloak from 'keycloak-js';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class KeycloakService {
  private keycloak = new Keycloak({
    url: environment.keycloak.url,
    realm: environment.keycloak.realm,
    clientId: environment.keycloak.clientId
  });
  
  async init(): Promise<void> {
    try {
      await this.keycloak.init({ onLoad: 'check-sso', checkLoginIframe: false });
    } catch (e) {
      console.error('Keycloak init error', e);
    }
  }
  
  getToken(): string { return this.keycloak.token ?? ''; }
  
  isAuthenticated(): boolean {
    return !!this.keycloak.token;
  }
  
  login(): void {
    this.keycloak.login({ redirectUri: window.location.origin + '/seats' });
  }
  
  logout(): void {
    this.keycloak.logout({ redirectUri: window.location.origin + '/login' });
  }
  
  hasRole(role: string): boolean {
    return this.keycloak.hasRealmRole(role);
  }

  getUsername(): string {
    return (this.keycloak.tokenParsed as any)?.preferred_username ?? 'User';
  }
}

