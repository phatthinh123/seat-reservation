import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface AdminBookingDto {
  id: string;
  userId: string;
  seatId: string;
  seatLabel: string;
  status: string;
  holdExpiresAt: string;
  createdAt: string;
  ageSeconds: number;
}

export interface AuditLogDto {
  id: string;
  actor: string;
  action: string;
  entityType: string;
  entityId: string;
  beforeState: string;
  afterState: string;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class AdminService {
  constructor(private http: HttpClient) {}

  getPendingBookings(): Observable<AdminBookingDto[]> {
    return this.http.get<AdminBookingDto[]>(`${environment.apiUrl}/api/admin/pending-bookings`);
  }

  reconcile(bookingId: string): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/api/admin/reconcile/${bookingId}`, {});
  }

  getAuditLogs(limit: number = 20): Observable<AuditLogDto[]> {
    return this.http.get<AuditLogDto[]>(`${environment.apiUrl}/api/admin/audit-logs?limit=${limit}`);
  }
}
