import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface BookingResponse {
  bookingId: string;
  seatId: string;
  seatLabel: string;
  status: string;
  holdExpiresAt: string;
  idempotencyKey: string;
}

@Injectable({ providedIn: 'root' })
export class BookingService {
  constructor(private http: HttpClient) {}

  holdSeat(seatId: string): Observable<BookingResponse> {
    const idempotencyKey = crypto.randomUUID();
    return this.http.post<BookingResponse>(
      `${environment.apiUrl}/api/bookings`,
      { seatId },
      { headers: { 'Idempotency-Key': idempotencyKey } }
    );
  }

  getBooking(bookingId: string): Observable<BookingResponse> {
    return this.http.get<BookingResponse>(`${environment.apiUrl}/api/bookings/${bookingId}`);
  }
}
