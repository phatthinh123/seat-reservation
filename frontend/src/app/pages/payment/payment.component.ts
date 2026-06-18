import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { BookingService, BookingResponse } from '../../core/services/booking.service';
import { PaymentService } from '../../core/services/payment.service';

@Component({
  selector: 'app-payment',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="container payment-container">
      <div class="card payment-card" *ngIf="booking">
        <h1 class="payment-title">Checkout</h1>
        
        <!-- Timer -->
        <div class="timer-section" [ngClass]="{'timer-expired': isExpired}">
          <div class="timer-label">{{ isExpired ? 'Hold Expired' : 'Time remaining to complete booking' }}</div>
          <div class="timer-clock">{{ formatTime(remainingSeconds) }}</div>
        </div>

        <div class="payment-details">
          <div class="detail-row">
            <span class="detail-label">Seat Selected:</span>
            <span class="detail-value highlight-text">{{ booking.seatLabel }}</span>
          </div>
          <div class="detail-row">
            <span class="detail-label">Total Price:</span>
            <span class="detail-value highlight-text">$50.00</span>
          </div>
        </div>

        <div class="payment-form">
          <div class="form-group">
            <label class="form-label">Cardholder Name</label>
            <input type="text" class="form-control" value="John Doe" disabled>
          </div>
          <div class="form-group">
            <label class="form-label">Card Number</label>
            <input type="text" class="form-control" value="•••• •••• •••• 4242" disabled>
          </div>

          <!-- Simulation Toggle -->
          <div class="simulation-section">
            <label class="checkbox-container">
              <input type="checkbox" [(ngModel)]="simulateFail">
              <span class="checkbox-checkmark"></span>
              <span class="simulation-label">⚠️ Simulate payment failure</span>
            </label>
            <p class="simulation-desc">If enabled, the payment gateway will simulate a failed charge webhook.</p>
            
            <label class="checkbox-container" style="margin-top: 12px;">
              <input type="checkbox" [(ngModel)]="simulateDelay">
              <span class="checkbox-checkmark"></span>
              <span class="simulation-label">⏱️ Simulate payment delay</span>
            </label>
            <p class="simulation-desc">If enabled, the payment webhook will be delayed by 60 seconds.</p>

            <label class="checkbox-container" style="margin-top: 12px;">
              <input type="checkbox" [(ngModel)]="simulateUiDelay">
              <span class="checkbox-checkmark"></span>
              <span class="simulation-label">⏳ Simulate UI loading delay</span>
            </label>
            <p class="simulation-desc">If enabled, the UI will show a loading spinner for 3 seconds before continuing.</p>
          </div>

          <div *ngIf="errorMessage" class="toast-error" style="margin-top: 16px;">
            <span>⚠️ {{ errorMessage }}</span>
          </div>

          <button 
            class="btn btn-primary pay-btn" 
            [disabled]="isExpired || isProcessing" 
            (click)="payNow()">
            <span *ngIf="isProcessing" class="spinner" style="margin-right: 8px;"></span>
            {{ isProcessing ? 'Processing Payment...' : 'Pay Now' }}
          </button>
        </div>
      </div>
      
      <div class="card loading-card" *ngIf="!booking && !errorMessage">
        <span class="spinner spinner-lg"></span>
        <p>Loading booking details...</p>
      </div>
      
      <div class="card error-card" *ngIf="!booking && errorMessage">
        <h2>Error</h2>
        <p>{{ errorMessage }}</p>
        <button class="btn btn-secondary" style="margin-top: 16px;" (click)="goBack()">Return to Seats</button>
      </div>
    </div>
  `,
  styles: [`
    .payment-container {
      display: flex;
      justify-content: center;
      align-items: center;
      flex: 1;
      padding-top: 48px;
    }
    .payment-card {
      max-width: 500px;
      width: 100%;
    }
    .payment-title {
      font-family: 'Outfit', sans-serif;
      font-size: 28px;
      font-weight: 800;
      margin-bottom: 24px;
      text-align: center;
    }
    .timer-section {
      background: rgba(245, 158, 11, 0.1);
      border: 1px solid rgba(245, 158, 11, 0.2);
      border-radius: 12px;
      padding: 16px;
      text-align: center;
      margin-bottom: 24px;
      transition: all 0.3s;
    }
    .timer-expired {
      background: rgba(239, 68, 68, 0.1);
      border-color: rgba(239, 68, 68, 0.2);
    }
    .timer-label {
      font-size: 13px;
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      margin-bottom: 4px;
    }
    .timer-clock {
      font-family: 'Outfit', sans-serif;
      font-size: 32px;
      font-weight: 700;
      color: var(--warning);
    }
    .timer-expired .timer-clock {
      color: var(--danger);
    }
    .payment-details {
      border-bottom: 1px solid var(--border);
      padding-bottom: 16px;
      margin-bottom: 24px;
    }
    .detail-row {
      display: flex;
      justify-content: space-between;
      margin-bottom: 12px;
      font-size: 16px;
    }
    .detail-label {
      color: var(--text-secondary);
    }
    .detail-value {
      font-weight: 600;
    }
    .highlight-text {
      color: var(--accent);
      font-family: 'Outfit', sans-serif;
      font-size: 18px;
    }
    .simulation-section {
      background: rgba(255, 255, 255, 0.02);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 16px;
      margin-top: 24px;
      margin-bottom: 24px;
    }
    .simulation-label {
      font-family: 'Outfit', sans-serif;
      font-weight: 600;
      color: var(--warning);
      font-size: 15px;
    }
    .simulation-desc {
      font-size: 13px;
      color: var(--text-secondary);
      margin-top: 8px;
      margin-left: 30px;
    }
    .pay-btn {
      height: 50px;
      font-size: 16px;
    }
    .loading-card, .error-card {
      max-width: 400px;
      width: 100%;
      text-align: center;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 16px;
    }
  `]
})
export class PaymentComponent implements OnInit, OnDestroy {
  booking: BookingResponse | null = null;
  remainingSeconds: number = 0;
  isExpired: boolean = false;
  simulateFail: boolean = false;
  simulateDelay: boolean = false;
  simulateUiDelay: boolean = false;
  isProcessing: boolean = false;
  errorMessage: string | null = null;
  private timerInterval: any;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private http: HttpClient,
    private bookingService: BookingService,
    private paymentService: PaymentService
  ) {}

  ngOnInit(): void {
    // Try to get booking from router state first (passed from seats page)
    const navigation = this.router.getCurrentNavigation();
    const state = navigation?.extras?.state || history.state;
    
    if (state?.booking) {
      this.booking = state.booking;
      this.startTimer();
    } else {
      // Fallback: get bookingId from URL and fetch from API
      this.route.params.subscribe(params => {
        const bookingId = params['bookingId'];
        if (bookingId) {
          this.loadBooking(bookingId);
        } else {
          this.errorMessage = 'Booking ID is missing.';
        }
      });
    }
  }

  ngOnDestroy(): void {
    if (this.timerInterval) {
      clearInterval(this.timerInterval);
    }
  }

  loadBooking(bookingId: string): void {
    this.bookingService.getBooking(bookingId).subscribe({
      next: (res) => {
        this.booking = res;
        this.startTimer();
      },
      error: (err) => {
        console.error('Error fetching booking', err);
        this.errorMessage = 'Could not load booking details. It may have expired or does not exist.';
      }
    });
  }

  startTimer(): void {
    if (!this.booking) return;

    this.updateCountdown();
    this.timerInterval = setInterval(() => {
      this.updateCountdown();
    }, 1000);
  }

  updateCountdown(): void {
    if (!this.booking) return;

    const expiryDate = this.parseExpiresAt(this.booking.holdExpiresAt);
    const now = new Date();
    const diff = Math.max(0, Math.floor((expiryDate.getTime() - now.getTime()) / 1000));
    
    this.remainingSeconds = diff;
    if (diff <= 0) {
      this.isExpired = true;
      clearInterval(this.timerInterval);
    }
  }

  parseExpiresAt(holdExpiresAt: string): Date {
    const localDate = new Date(holdExpiresAt);
    const now = new Date();
    const diffMinutes = (localDate.getTime() - now.getTime()) / 60000;
    
    if (diffMinutes < -30 || diffMinutes > 30) {
      const utcDate = new Date(holdExpiresAt + 'Z');
      const utcDiffMinutes = (utcDate.getTime() - now.getTime()) / 60000;
      if (Math.abs(utcDiffMinutes) < Math.abs(diffMinutes)) {
        return utcDate;
      }
    }
    return localDate;
  }

  formatTime(seconds: number): string {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  }

  payNow(): void {
    if (!this.booking || this.isExpired || this.isProcessing) return;

    this.isProcessing = true;
    this.errorMessage = null;

    const executePayment = () => {
      this.paymentService.initiatePayment(this.booking!.bookingId, this.simulateFail, this.simulateDelay).subscribe({
        next: (_res) => {
          this.router.navigate(['/confirmation'], {
            queryParams: {
              status: 'pending',
              bookingId: this.booking!.bookingId
            }
          });
        },
        error: (err) => {
          console.error('Payment initiation error', err);
          this.isProcessing = false;
          this.errorMessage = err.error?.message || 'Payment initiation failed. Please try again.';
        }
      });
    };

    if (this.simulateUiDelay) {
      setTimeout(() => executePayment(), 3000);
    } else {
      executePayment();
    }
  }

  goBack(): void {
    this.router.navigate(['/seats']);
  }
}
