import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { KeycloakService } from '../../core/auth/keycloak.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="login-container">
      <div class="card login-card">
        <h1 class="brand-title">⚡ Linkz Seat Reservation</h1>
        <p class="brand-subtitle">Reserve your seat securely and instantly.</p>
        <button class="btn btn-primary" (click)="login()">Login with Keycloak</button>
      </div>
    </div>
  `,
  styles: [`
    .login-container {
      display: flex;
      align-items: center;
      justify-content: center;
      flex: 1;
      padding: 24px;
    }
    .login-card {
      max-width: 400px;
      width: 100%;
      text-align: center;
      box-shadow: 0 20px 40px rgba(0, 0, 0, 0.4);
    }
    .brand-title {
      font-family: 'Outfit', sans-serif;
      font-size: 28px;
      font-weight: 800;
      margin-bottom: 8px;
      background: linear-gradient(135deg, #fff 0%, #6366f1 100%);
      -webkit-background-clip: text;
      -webkit-text-fill-color: transparent;
    }
    .brand-subtitle {
      color: var(--text-secondary);
      margin-bottom: 32px;
      font-size: 15px;
    }
  `]
})
export class LoginComponent implements OnInit {
  constructor(private keycloakService: KeycloakService, private router: Router) {}

  ngOnInit(): void {
    if (this.keycloakService.isAuthenticated()) {
      this.router.navigate(['/seats']);
    }
  }

  login(): void {
    this.keycloakService.login();
  }
}
