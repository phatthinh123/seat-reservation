# Agent: Milestone 4 — Angular Frontend

## Your Role
You are the frontend agent. Backend is fully working. Your job is to build all 5 Angular
pages and wire them to the backend API.

## Reference Files
- `IMPLEMENTATION_PLAN.md` — Section 4.7, 4.9 (DTOs), Section 8 (API table), Section 4.11 (1s polling)

## Design Requirements

The UI must look **premium and professional**. Use:
- Dark theme with a sophisticated color palette (deep navy/slate background, vibrant accent)
- Google Font: Inter or Outfit
- Glassmorphism cards for seat display
- Smooth hover animations and transitions
- Seat status visually distinct: green glow (AVAILABLE), amber pulse (HELD), red locked (RESERVED)

## Pages to Build

### 1. `/login` — Login Page
- Simple page with app branding "tpthinh Seat Reservation"
- "Login with Keycloak" button → triggers Keycloak redirect
- If already authenticated → redirect to `/seats`

### 2. `/seats` — Seat Selection Page
- 3 seat cards displayed in a row
- Each card shows: seat label (A1, A2, A3), status badge, and "Hold Seat" button
- Status colors:
  - AVAILABLE: green, "Hold Seat" button enabled
  - HELD: amber/orange, "Currently Held" label (no button)
  - RESERVED: red/grey, "Reserved" label (no button)
- **Polls every 1 second** via `setInterval` calling `SeatService.getSeats()`
- On click "Hold Seat" → POST /api/bookings → navigate to `/payment/:bookingId`
- Show timer countdown if user holds a seat (uses `holdExpiresAt` from booking response)
- Show error toast if seat was taken between render and click (409 response)

### 3. `/payment/:bookingId` — Payment Page
- Show selected seat info (label, price: $50.00 hardcoded)
- Show hold countdown timer (counts down to holdExpiresAt — must update every second)
- Payment form with: card number (display only — mock), cardholder name (display only)
- **"Simulate payment failure" toggle** (checkbox) — prominent, clearly labeled
- "Pay Now" button → POST /api/bookings/:id/payment with `{ simulateFail: boolean }`
- While processing: show loading spinner
- On response: navigate to `/confirmation?status=pending&bookingId=...`
- **Poll booking status** every 2 seconds after payment initiation (waiting for webhook)

### 4. `/confirmation` — Confirmation Page
- Read `?status=` and `?bookingId=` from query params
- While status is PENDING: show "Processing payment..." with spinner, poll every 2s
- On CONFIRMED: show success animation + "Seat A1 successfully reserved! 🎉"
- On EXPIRED/FAILED: show failure message + "Return to seats" button
- Stop polling when terminal state reached

### 5. `/admin` — Admin Dashboard (ADMIN role only)
- Route guard: redirect to /seats if not ADMIN role
- Two sections:
  **Pending Bookings Table**:
  - Columns: Booking ID (truncated), User, Seat, Created At, Hold Expires, Age (mins)
  - "Reconcile" button per row → POST /api/admin/reconcile/:id → refresh table
  - Auto-refreshes every 10 seconds
  
  **Recent Audit Log**:
  - Columns: Time, Actor, Action, Entity Type, Entity ID
  - Color-coded by action type (green=success, orange=warning, red=error)
  - Last 20 entries, auto-refreshes every 5 seconds

## Services to Create

```typescript
// core/services/seat.service.ts
@Injectable({ providedIn: 'root' })
export class SeatService {
  getSeats(): Observable<SeatResponse[]> {
    return this.http.get<SeatResponse[]>('/api/seats');
  }
}

// core/services/booking.service.ts
export class BookingService {
  holdSeat(seatId: string): Observable<BookingResponse> {
    return this.http.post<BookingResponse>('/api/bookings', { seatId });
  }
  getBooking(bookingId: string): Observable<BookingResponse> {
    return this.http.get<BookingResponse>(`/api/bookings/${bookingId}`);
  }
}

// core/services/payment.service.ts
export class PaymentService {
  initiatePayment(bookingId: string, simulateFail: boolean): Observable<PaymentResponse> {
    return this.http.post<PaymentResponse>(`/api/bookings/${bookingId}/payment`,
      { simulateFail });
  }
}

// core/services/admin.service.ts
export class AdminService {
  getPendingBookings(): Observable<AdminBookingDto[]> { ... }
  reconcile(bookingId: string): Observable<void> { ... }
  getAuditLogs(limit: number): Observable<AuditLogDto[]> { ... }
}
```

## Environment Config

`frontend/src/environments/environment.ts`:
```typescript
export const environment = {
  production: false,
  apiUrl: 'http://localhost:8080',
  keycloak: {
    url: 'http://localhost:8180',
    realm: 'seat-reservation',
    clientId: 'seat-reservation-app'
  }
};
```

## nginx.conf

```nginx
server {
  listen 80;
  location / {
    root /usr/share/nginx/html;
    try_files $uri $uri/ /index.html;
  }
  location /api {
    proxy_pass http://backend:8080;
  }
}
```

## CSS/Styling

Use a single `styles.css` with CSS variables:
```css
:root {
  --bg-primary: #0a0f1e;
  --bg-card: rgba(255,255,255,0.05);
  --accent: #6366f1;
  --accent-hover: #4f46e5;
  --success: #10b981;
  --warning: #f59e0b;
  --danger: #ef4444;
  --text-primary: #f1f5f9;
  --text-secondary: #94a3b8;
  --border: rgba(255,255,255,0.1);
}

body { background: var(--bg-primary); color: var(--text-primary); font-family: 'Inter', sans-serif; }
```

## Verification Steps

```bash
# 1. Build Angular
cd frontend && npm run build
# Should complete without errors

# 2. Start all services
docker compose up -d

# 3. Open browser to http://localhost:4200
# Should redirect to Keycloak login

# 4. Login as user@tpthinh.com / User1234!
# Should see 3 seat cards

# 5. Click "Hold Seat" on A1
# Should navigate to /payment/:bookingId
# Should show hold countdown timer

# 6. Click "Pay Now" (without simulate failure)
# Should navigate to /confirmation
# Should show "Processing..." then "Reserved!" within ~5 seconds

# 7. Return to seats — A1 should be RESERVED (red)

# 8. Login as admin@tpthinh.com in new browser tab
# Navigate to /admin
# Should see audit log with all events
```

## Definition of Done

✅ All 5 pages render without console errors
✅ Login → Keycloak → back to /seats works
✅ Seat cards update every 1s (verify by holding a seat in another tab and watching)
✅ Payment flow: hold → pay → confirmation → RESERVED
✅ Simulate failure: shows failure state on confirmation page
✅ Hold countdown timer counts down correctly
✅ Admin page: pending bookings table + reconcile button + audit log
✅ Admin page redirects non-admin to /seats
✅ Design looks premium (dark theme, colored status badges, smooth transitions)
