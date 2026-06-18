import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface PaymentResponse {
  paymentId: string;
  bookingId: string;
  status: string;
}

@Injectable({ providedIn: 'root' })
export class PaymentService {
  constructor(private http: HttpClient) {}

  initiatePayment(bookingId: string, simulateFail: boolean, simulateDelay: boolean = false): Observable<PaymentResponse> {
    return this.http.post<PaymentResponse>(
      `${environment.apiUrl}/api/bookings/${bookingId}/payment`,
      { simulateFail, simulateDelay }
    );
  }
}
