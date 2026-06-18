import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { SeatService, SeatResponse } from '../../core/services/seat.service';
import { BookingService } from '../../core/services/booking.service';

@Component({
  selector: 'app-seats',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="container seats-container">
      <div class="seats-header">
        <h1>Select a Seat</h1>
        <p>Choose one of our available premium seats below.</p>
      </div>

      <div *ngIf="toastMessage" class="toast-error">
        <span>⚠️ {{ toastMessage }}</span>
      </div>

      <div class="seats-row">
        <div *ngFor="let seat of seats" class="card seat-card" [ngClass]="getSeatClass(seat)">
          <div class="seat-label">{{ seat.label }}</div>
          <div class="seat-badge-wrapper">
            <span class="badge" [ngClass]="getBadgeClass(seat)">
              {{ seat.status }}
            </span>
          </div>
          <div class="seat-action">
            <button 
              *ngIf="seat.status === 'AVAILABLE'" 
              class="btn btn-primary" 
              (click)="holdSeat(seat.id)">
              Hold Seat
            </button>
            <span *ngIf="seat.status === 'HELD'" class="held-status-text">
              Currently Held
            </span>
            <span *ngIf="seat.status === 'RESERVED'" class="reserved-status-text">
              Reserved
            </span>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .seats-container {
      display: flex;
      flex-direction: column;
      align-items: center;
      flex: 1;
      padding-top: 48px;
    }
    .seats-header {
      text-align: center;
      margin-bottom: 40px;
    }
    .seats-header h1 {
      font-family: 'Outfit', sans-serif;
      font-size: 36px;
      font-weight: 800;
      margin-bottom: 12px;
    }
    .seats-header p {
      color: var(--text-secondary);
      font-size: 16px;
    }
    .seats-row {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 24px;
      width: 100%;
      max-width: 900px;
    }
    .seat-card {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: space-between;
      min-height: 250px;
      padding: 32px;
      position: relative;
      overflow: hidden;
    }
    
    .seat-available {
      border-color: rgba(16, 185, 129, 0.2);
      box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37), 0 0 15px rgba(16, 185, 129, 0.05);
    }
    .seat-available:hover {
      border-color: var(--success);
      box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37), 0 0 25px rgba(16, 185, 129, 0.2);
    }
    
    .seat-held {
      border-color: rgba(245, 158, 11, 0.2);
      box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37);
      animation: pulse-border-held 2s infinite;
    }
    
    @keyframes pulse-border-held {
      0% { border-color: rgba(245, 158, 11, 0.2); box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37), 0 0 5px rgba(245, 158, 11, 0.05); }
      50% { border-color: rgba(245, 158, 11, 0.6); box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37), 0 0 20px rgba(245, 158, 11, 0.25); }
      100% { border-color: rgba(245, 158, 11, 0.2); box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37), 0 0 5px rgba(245, 158, 11, 0.05); }
    }
    
    .seat-reserved {
      border-color: rgba(239, 68, 68, 0.15);
      background: rgba(255, 255, 255, 0.01);
      opacity: 0.75;
    }
    
    .seat-label {
      font-family: 'Outfit', sans-serif;
      font-size: 48px;
      font-weight: 800;
      color: var(--text-primary);
      margin-bottom: 12px;
      letter-spacing: -0.02em;
    }
    .seat-badge-wrapper {
      margin-bottom: 24px;
    }
    .seat-action {
      width: 100%;
      text-align: center;
    }
    .held-status-text {
      font-family: 'Outfit', sans-serif;
      font-weight: 600;
      color: var(--warning);
      display: inline-block;
      padding: 12px;
      font-size: 15px;
    }
    .reserved-status-text {
      font-family: 'Outfit', sans-serif;
      font-weight: 600;
      color: var(--text-muted);
      display: inline-block;
      padding: 12px;
      font-size: 15px;
    }
  `]
})
export class SeatsComponent implements OnInit, OnDestroy {
  seats: SeatResponse[] = [];
  toastMessage: string | null = null;
  private pollInterval: any;
  private toastTimeout: any;

  constructor(
    private seatService: SeatService,
    private bookingService: BookingService,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.loadSeats();
    // Poll every 1 second so users see live seat status changes immediately
    this.pollInterval = setInterval(() => {
      this.loadSeats();
    }, 1000);
  }

  ngOnDestroy(): void {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
    }
    if (this.toastTimeout) {
      clearTimeout(this.toastTimeout);
    }
  }

  loadSeats(): void {
    this.seatService.getSeats().subscribe({
      next: (seats) => {
        this.seats = seats;
      },
      error: (err) => {
        console.error('Error fetching seats', err);
      }
    });
  }

  holdSeat(seatId: string): void {
    this.bookingService.holdSeat(seatId).subscribe({
      next: (res) => {
        this.router.navigate(['/payment', res.bookingId], { state: { booking: res } });
      },
      error: (err) => {
        console.error('Error holding seat', err);
        if (err.status === 409) {
          this.showToast('This seat was taken by another user. Please select another seat.');
        } else {
          this.showToast(err.error?.message || 'Failed to hold seat. Please try again.');
        }
      }
    });
  }

  getSeatClass(seat: SeatResponse): string {
    switch (seat.status) {
      case 'AVAILABLE': return 'seat-available';
      case 'HELD': return 'seat-held';
      case 'RESERVED': return 'seat-reserved';
      default: return '';
    }
  }

  getBadgeClass(seat: SeatResponse): string {
    switch (seat.status) {
      case 'AVAILABLE': return 'badge-success';
      case 'HELD': return 'badge-warning';
      case 'RESERVED': return 'badge-danger';
      default: return '';
    }
  }

  showToast(msg: string): void {
    this.toastMessage = msg;
    if (this.toastTimeout) {
      clearTimeout(this.toastTimeout);
    }
    this.toastTimeout = setTimeout(() => {
      this.toastMessage = null;
    }, 5000);
  }
}
