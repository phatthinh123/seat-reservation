import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface SeatResponse {
  id: string;
  label: string;
  status: 'AVAILABLE' | 'HELD' | 'RESERVED';
  heldBy?: string;
}

@Injectable({ providedIn: 'root' })
export class SeatService {
  constructor(private http: HttpClient) {}

  getSeats(): Observable<SeatResponse[]> {
    return this.http.get<SeatResponse[]>(`${environment.apiUrl}/api/seats`);
  }
}
