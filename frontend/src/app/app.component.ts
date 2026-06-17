import { Component } from '@angular/core';
import { RouterOutlet, RouterLink } from '@angular/router';
import { CommonModule } from '@angular/common';
import { KeycloakService } from './core/auth/keycloak.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, CommonModule],
  template: `
    <header class="app-header" *ngIf="isAuthenticated()">
      <div class="header-container">
        <div class="brand">
          <span>⚡ Linkz Seat Reservation</span>
        </div>
        <nav class="user-nav">
          <a class="logout-btn" routerLink="/seats" style="text-decoration: none;">Seats</a>
          <a class="logout-btn" routerLink="/admin" *ngIf="isAdmin()" style="text-decoration: none;">Admin</a>
          <span class="username">👤 {{ getUsername() }}</span>
          <button class="logout-btn" (click)="logout()">Logout</button>
        </nav>
      </div>
    </header>
    <main>
      <router-outlet></router-outlet>
    </main>
  `,
  styles: [`
    main {
      min-height: 100vh;
      display: flex;
      flex-direction: column;
    }
  `]
})
export class AppComponent {
  title = 'seat-reservation';

  constructor(private keycloakService: KeycloakService) {}

  isAuthenticated(): boolean {
    return this.keycloakService.isAuthenticated();
  }

  isAdmin(): boolean {
    return this.keycloakService.hasRole('ADMIN');
  }

  getUsername(): string {
    return this.keycloakService.getUsername();
  }

  logout(): void {
    this.keycloakService.logout();
  }
}

