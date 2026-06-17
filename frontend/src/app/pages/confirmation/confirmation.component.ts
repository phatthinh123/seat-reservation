import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { BookingService, BookingResponse } from '../../core/services/booking.service';

@Component({
  selector: 'app-confirmation',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="container confirmation-container">
      <div class="card status-card" [ngClass]="getStatusCardClass()">
        
        <!-- Processing State -->
        <div class="status-content" *ngIf="status === 'PENDING' || status === 'pending'">
          <span class="spinner spinner-lg"></span>
          <h1 class="status-title">Processing payment...</h1>
          <p class="status-desc">We are processing your payment request. This should only take a few seconds.</p>
        </div>

        <!-- Confirmed State -->
        <div class="status-content" *ngIf="status === 'CONFIRMED'">
          <div class="success-icon">🎉</div>
          <h1 class="status-title text-success">Reservation Confirmed!</h1>
          <p class="status-desc" *ngIf="booking">
            Seat <span class="highlight-text">{{ booking.seatLabel }}</span> is successfully reserved!
          </p>
          <div class="booking-receipt" *ngIf="booking">
            <div class="receipt-row">
              <span class="receipt-label">Booking ID:</span>
              <span class="receipt-value">{{ booking.bookingId }}</span>
            </div>
            <div class="receipt-row" *ngIf="booking.idempotencyKey">
              <span class="receipt-label">Reference Key:</span>
              <span class="receipt-value">{{ booking.idempotencyKey | slice:0:8 }}...</span>
            </div>
          </div>
          <button class="btn btn-primary action-btn" (click)="returnToSeats()">Return to Seats</button>
        </div>

        <!-- Failed / Expired / Cancelled State -->
        <div class="status-content" *ngIf="status === 'FAILED' || status === 'EXPIRED' || status === 'CANCELLED'">
          <div class="fail-icon">❌</div>
          <h1 class="status-title text-danger">
            {{ status === 'EXPIRED' ? 'Hold Expired' : status === 'CANCELLED' ? 'Payment Cancelled' : 'Payment Failed' }}
          </h1>
          <p class="status-desc">
            {{ getFailureReason() }}
          </p>
          <button class="btn btn-secondary action-btn" (click)="returnToSeats()">Return to Seats</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .confirmation-container {
      display: flex;
      justify-content: center;
      align-items: center;
      flex: 1;
      padding-top: 48px;
    }
    .status-card {
      max-width: 500px;
      width: 100%;
      text-align: center;
      transition: all 0.5s ease;
    }
    .card-success {
      border-color: rgba(16, 185, 129, 0.3);
      box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37), 0 0 30px rgba(16, 185, 129, 0.1);
    }
    .card-danger {
      border-color: rgba(239, 68, 68, 0.3);
      box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.37), 0 0 30px rgba(239, 68, 68, 0.1);
    }
    .status-content {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 20px;
    }
    .status-title {
      font-family: 'Outfit', sans-serif;
      font-size: 26px;
      font-weight: 800;
    }
    .text-success {
      color: var(--success);
    }
    .text-danger {
      color: var(--danger);
    }
    .status-desc {
      color: var(--text-secondary);
      font-size: 15px;
      line-height: 1.5;
    }
    .success-icon {
      font-size: 64px;
      animation: bounce 1s infinite alternate;
    }
    .fail-icon {
      font-size: 64px;
      animation: shake 0.5s ease-in-out;
    }
    .highlight-text {
      color: var(--accent);
      font-weight: 700;
    }
    .booking-receipt {
      background: rgba(0,0,0,0.2);
      border: 1px solid var(--border);
      border-radius: 12px;
      width: 100%;
      padding: 16px;
      margin-top: 8px;
      margin-bottom: 8px;
      text-align: left;
    }
    .receipt-row {
      display: flex;
      justify-content: space-between;
      margin-bottom: 8px;
      font-size: 13px;
    }
    .receipt-row:last-child {
      margin-bottom: 0;
    }
    .receipt-label {
      color: var(--text-secondary);
    }
    .receipt-value {
      color: var(--text-primary);
      font-family: monospace;
      font-weight: 600;
    }
    .action-btn {
      margin-top: 12px;
      width: auto;
      min-width: 180px;
    }

    @keyframes bounce {
      from { transform: scale(1); }
      to { transform: scale(1.15); }
    }
    @keyframes shake {
      0%, 100% { transform: translateX(0); }
      20%, 60% { transform: translateX(-6px); }
      40%, 80% { transform: translateX(6px); }
    }
  `]
})
export class ConfirmationComponent implements OnInit, OnDestroy {
  status: string = 'PENDING';
  bookingId: string | null = null;
  booking: BookingResponse | null = null;
  private pollInterval: any;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private bookingService: BookingService
  ) {}

  ngOnInit(): void {
    this.route.queryParams.subscribe(params => {
      this.bookingId = params['bookingId'];
      const initialStatus = params['status'];
      if (initialStatus) {
        this.status = initialStatus.toUpperCase();
      }

      if (this.bookingId) {
        this.loadBookingStatus();
        if (this.status === 'PENDING') {
          this.startPolling();
        }
      }
    });
  }

  ngOnDestroy(): void {
    this.stopPolling();
  }

  loadBookingStatus(): void {
    if (!this.bookingId) return;

    this.bookingService.getBooking(this.bookingId).subscribe({
      next: (booking) => {
        this.booking = booking;
        this.status = booking.status.toUpperCase();
        if (this.status !== 'PENDING') {
          this.stopPolling();
        }
      },
      error: (err) => {
        console.error('Error loading booking status', err);
      }
    });
  }

  startPolling(): void {
    this.stopPolling();
    this.pollInterval = setInterval(() => {
      this.loadBookingStatus();
    }, 2000);
  }

  stopPolling(): void {
    if (this.pollInterval) {
      clearInterval(this.pollInterval);
      this.pollInterval = null;
    }
  }

  getStatusCardClass(): string {
    if (this.status === 'CONFIRMED') return 'card-success';
    if (this.status === 'FAILED' || this.status === 'EXPIRED' || this.status === 'CANCELLED') return 'card-danger';
    return '';
  }

  getFailureReason(): string {
    switch (this.status) {
      case 'EXPIRED':
        return 'Your seat hold expired before payment could be finalized. The 10-minute hold window has passed.';
      case 'CANCELLED':
        return 'The payment was cancelled. The seat has been released and is now available for others.';
      case 'FAILED':
      default:
        return 'The payment transaction failed. Please try again or use another payment method.';
    }
  }

  returnToSeats(): void {
    this.router.navigate(['/seats']);
  }
}
