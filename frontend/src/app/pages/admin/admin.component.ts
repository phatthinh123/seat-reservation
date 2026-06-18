import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { AdminService, AdminBookingDto, AuditLogDto } from '../../core/services/admin.service';

@Component({
  selector: 'app-admin',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="container admin-container">
      <div class="admin-header">
        <h1>Admin Dashboard</h1>
        <p>Monitor pending bookings, trigger reconciliation, and review system audit logs.</p>
      </div>

      <!-- Toast Alerts -->
      <div *ngIf="successMessage" class="toast-success-banner">
        <span>✅ {{ successMessage }}</span>
      </div>
      <div *ngIf="errorMessage" class="toast-error">
        <span>⚠️ {{ errorMessage }}</span>
      </div>

      <div class="admin-grid">
        <!-- Pending Bookings Card -->
        <div class="card admin-card">
          <div class="card-header">
            <h2>Pending Bookings</h2>
            <span class="refresh-indicator">Auto-refreshes every 10s</span>
          </div>
          <div class="table-wrapper">
            <table *ngIf="pendingBookings.length > 0; else noBookings">
              <thead>
                <tr>
                  <th>Booking ID</th>
                  <th>User</th>
                  <th>Seat</th>
                  <th>Created At</th>
                  <th>Hold Expires</th>
                  <th>Age</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let booking of pendingBookings">
                  <td class="mono-text" [title]="booking.id">{{ booking.id | slice:0:8 }}...</td>
                  <td>{{ booking.userId }}</td>
                  <td class="seat-label-td">{{ booking.seatLabel }}</td>
                  <td>{{ formatDate(booking.createdAt) }}</td>
                  <td>{{ formatDate(booking.holdExpiresAt) }}</td>
                  <td>{{ formatAge(booking.ageSeconds) }}</td>
                  <td>
                    <button class="btn btn-secondary btn-sm" (click)="reconcile(booking.id)" [disabled]="reconcilingIds.has(booking.id)">
                      <span *ngIf="reconcilingIds.has(booking.id)" class="spinner spinner-xs"></span>
                      Reconcile
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
            <ng-template #noBookings>
              <div class="no-data">No pending bookings found.</div>
            </ng-template>
          </div>
        </div>

        <!-- Audit Log Card -->
        <div class="card admin-card" style="margin-top: 32px;">
          <div class="card-header-actions">
            <h2>System Audit Log</h2>
            <div class="filter-tabs">
              <button class="tab-btn" [class.active]="activeTab === 'ALL'" (click)="activeTab = 'ALL'">All</button>
              <button class="tab-btn" [class.active]="activeTab === 'BOOKING'" (click)="activeTab = 'BOOKING'">Bookings</button>
              <button class="tab-btn" [class.active]="activeTab === 'SEAT'" (click)="activeTab = 'SEAT'">Seats</button>
              <button class="tab-btn" [class.active]="activeTab === 'PAYMENT'" (click)="activeTab = 'PAYMENT'">Payments</button>
              <button class="tab-btn" [class.active]="activeTab === 'WEBHOOK'" (click)="activeTab = 'WEBHOOK'">Webhooks</button>
              <button class="tab-btn" [class.active]="activeTab === 'SYSTEM'" (click)="activeTab = 'SYSTEM'">System</button>
            </div>
            <span class="refresh-indicator">Auto-refreshes every 5s</span>
          </div>
          <div class="table-wrapper">
            <table *ngIf="filteredAuditLogs.length > 0; else noLogs">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Actor</th>
                  <th>Action</th>
                  <th>Entity Type</th>
                  <th>Entity ID</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let log of filteredAuditLogs">
                  <td>{{ formatDate(log.createdAt) }}</td>
                  <td>{{ log.actor }}</td>
                  <td>
                    <span class="badge" [ngClass]="getActionBadgeClass(log.action)">
                      {{ log.action }}
                    </span>
                  </td>
                  <td>{{ log.entityType }}</td>
                  <td class="mono-text" [title]="log.entityId">{{ log.entityId | slice:0:8 }}...</td>
                  <td>
                    <button class="btn btn-secondary btn-xs" (click)="viewDetails(log)">View Details</button>
                  </td>
                </tr>
              </tbody>
            </table>
            <ng-template #noLogs>
              <div class="no-data">No audit logs found for category: {{ activeTab }}.</div>
            </ng-template>
          </div>
        </div>
      </div>
    </div>

    <!-- Details Modal -->
    <div *ngIf="selectedLog" class="modal-overlay" (click)="closeModal()">
      <div class="modal-content card" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h3>Audit Log Details</h3>
          <button class="close-btn" (click)="closeModal()">&times;</button>
        </div>
        <div class="modal-body">
          <div class="modal-meta-grid">
            <div><strong>Actor:</strong> {{ selectedLog.actor }}</div>
            <div><strong>Action:</strong> {{ selectedLog.action }}</div>
            <div><strong>Entity Type:</strong> {{ selectedLog.entityType }}</div>
            <div><strong>Entity ID:</strong> <span class="mono-text">{{ selectedLog.entityId }}</span></div>
            <div><strong>Time:</strong> {{ formatDate(selectedLog.createdAt) }}</div>
          </div>
          
          <div class="payload-comparison">
            <div class="payload-box">
              <h4>Before State</h4>
              <pre><code>{{ formatPayload(selectedLog.beforeState) }}</code></pre>
            </div>
            <div class="payload-box">
              <h4>After State</h4>
              <pre><code>{{ formatPayload(selectedLog.afterState) }}</code></pre>
            </div>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .admin-container {
      padding-top: 48px;
      padding-bottom: 48px;
    }
    .admin-header {
      margin-bottom: 32px;
    }
    .admin-header h1 {
      font-family: 'Outfit', sans-serif;
      font-size: 36px;
      font-weight: 800;
      margin-bottom: 8px;
    }
    .admin-header p {
      color: var(--text-secondary);
      font-size: 16px;
    }
    .card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
      border-bottom: 1px solid var(--border);
      padding-bottom: 16px;
    }
    .card-header h2 {
      font-family: 'Outfit', sans-serif;
      font-size: 20px;
      font-weight: 700;
    }
    .card-header-actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 24px;
      border-bottom: 1px solid var(--border);
      padding-bottom: 16px;
      flex-wrap: wrap;
      gap: 16px;
    }
    .card-header-actions h2 {
      font-family: 'Outfit', sans-serif;
      font-size: 20px;
      font-weight: 700;
    }
    .filter-tabs {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .tab-btn {
      background: transparent;
      border: 1px solid var(--border);
      color: var(--text-secondary);
      padding: 6px 14px;
      border-radius: 6px;
      cursor: pointer;
      font-size: 13px;
      font-weight: 500;
      transition: all 0.2s ease;
    }
    .tab-btn:hover {
      background: var(--bg-card-hover);
      color: var(--text-primary);
    }
    .tab-btn.active {
      background: var(--accent);
      border-color: var(--accent);
      color: #ffffff;
      box-shadow: 0 0 12px var(--accent-glow);
    }
    .refresh-indicator {
      font-size: 12px;
      color: var(--text-muted);
      background: rgba(255, 255, 255, 0.02);
      border: 1px solid var(--border);
      padding: 4px 8px;
      border-radius: 6px;
    }
    .table-wrapper {
      width: 100%;
      overflow-x: auto;
    }
    .mono-text {
      font-family: monospace;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .seat-label-td {
      font-weight: 700;
      color: var(--accent);
    }
    .no-data {
      text-align: center;
      padding: 48px 0;
      color: var(--text-muted);
      font-size: 15px;
    }
    .btn-sm {
      padding: 6px 12px;
      font-size: 13px;
      border-radius: 6px;
      width: auto;
    }
    .btn-xs {
      padding: 4px 8px;
      font-size: 11px;
      border-radius: 4px;
      width: auto;
      background: rgba(255, 255, 255, 0.03);
      border: 1px solid var(--border);
      color: var(--text-secondary);
      cursor: pointer;
      transition: all 0.2s;
    }
    .btn-xs:hover {
      background: var(--bg-card-hover);
      color: var(--text-primary);
      border-color: var(--border-focus);
    }
    .toast-success-banner {
      background: rgba(16, 185, 129, 0.15);
      border-left: 5px solid var(--success);
      color: var(--text-primary);
      padding: 16px;
      border-radius: 8px;
      margin-bottom: 24px;
      font-weight: 500;
      box-shadow: var(--shadow);
    }
    .spinner-xs {
      width: 14px;
      height: 14px;
      border-width: 2px;
      margin-right: 6px;
    }

    /* Modal Styles */
    .modal-overlay {
      position: fixed;
      top: 0;
      left: 0;
      width: 100vw;
      height: 100vh;
      background: rgba(0, 0, 0, 0.7);
      backdrop-filter: blur(8px);
      display: flex;
      justify-content: center;
      align-items: center;
      z-index: 1000;
      animation: fadeIn 0.2s ease;
    }
    .modal-content {
      width: 90%;
      max-width: 850px;
      max-height: 85vh;
      background: var(--bg-secondary);
      border: 1px solid var(--border);
      border-radius: 12px;
      box-shadow: var(--shadow);
      display: flex;
      flex-direction: column;
      animation: slideUp 0.2s ease;
    }
    .modal-header {
      padding: 20px;
      border-bottom: 1px solid var(--border);
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .modal-header h3 {
      font-family: 'Outfit', sans-serif;
      font-size: 18px;
      font-weight: 700;
    }
    .close-btn {
      background: transparent;
      border: none;
      color: var(--text-secondary);
      font-size: 24px;
      cursor: pointer;
      line-height: 1;
    }
    .close-btn:hover {
      color: var(--text-primary);
    }
    .modal-body {
      padding: 20px;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    .modal-meta-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 12px;
      background: rgba(255, 255, 255, 0.02);
      border: 1px solid var(--border);
      padding: 16px;
      border-radius: 8px;
      font-size: 14px;
    }
    .payload-comparison {
      display: grid;
      grid-template-columns: 1fr;
      gap: 20px;
    }
    @media (min-width: 768px) {
      .payload-comparison {
        grid-template-columns: 1fr 1fr;
      }
    }
    .payload-box {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .payload-box h4 {
      font-size: 14px;
      font-weight: 600;
      color: var(--text-secondary);
    }
    .payload-box pre {
      background: #070b15;
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 16px;
      overflow: auto;
      max-height: 320px;
      font-size: 12px;
      line-height: 1.5;
      color: #a5b4fc;
    }

    @keyframes fadeIn {
      from { opacity: 0; }
      to { opacity: 1; }
    }
    @keyframes slideUp {
      from { transform: translateY(20px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }
  `]
})
export class AdminComponent implements OnInit, OnDestroy {
  pendingBookings: AdminBookingDto[] = [];
  auditLogs: AuditLogDto[] = [];
  reconcilingIds: Set<string> = new Set();
  
  activeTab: string = 'ALL';
  selectedLog: AuditLogDto | null = null;

  successMessage: string | null = null;
  errorMessage: string | null = null;

  private bookingsInterval: any;
  private logsInterval: any;
  private alertTimeout: any;

  constructor(private adminService: AdminService) {}

  ngOnInit(): void {
    this.loadBookings();
    this.loadLogs();

    // Pending bookings refresh every 10s
    this.bookingsInterval = setInterval(() => {
      this.loadBookings();
    }, 10000);

    // Audit logs refresh every 5s
    this.logsInterval = setInterval(() => {
      this.loadLogs();
    }, 5000);
  }

  ngOnDestroy(): void {
    if (this.bookingsInterval) clearInterval(this.bookingsInterval);
    if (this.logsInterval) clearInterval(this.logsInterval);
    if (this.alertTimeout) clearTimeout(this.alertTimeout);
  }

  get filteredAuditLogs(): AuditLogDto[] {
    if (this.activeTab === 'ALL') {
      return this.auditLogs;
    }
    return this.auditLogs.filter(log => log.entityType === this.activeTab);
  }

  loadBookings(): void {
    this.adminService.getPendingBookings().subscribe({
      next: (bookings) => {
        this.pendingBookings = bookings;
      },
      error: (err) => {
        console.error('Error fetching admin bookings', err);
      }
    });
  }

  loadLogs(): void {
    this.adminService.getAuditLogs(50).subscribe({ // Fetch slightly more logs so filtering results are representative
      next: (logs) => {
        this.auditLogs = logs;
      },
      error: (err) => {
        console.error('Error fetching audit logs', err);
      }
    });
  }

  reconcile(bookingId: string): void {
    this.reconcilingIds.add(bookingId);
    this.errorMessage = null;
    this.successMessage = null;

    this.adminService.reconcile(bookingId).subscribe({
      next: () => {
        this.reconcilingIds.delete(bookingId);
        this.showSuccess('Booking successfully reconciled.');
        this.loadBookings();
        this.loadLogs();
      },
      error: (err) => {
        this.reconcilingIds.delete(bookingId);
        console.error('Reconciliation failed', err);
        this.showError(err.error?.message || 'Reconciliation failed. Seat hold may have expired or payment was not found.');
      }
    });
  }

  viewDetails(log: AuditLogDto): void {
    this.selectedLog = log;
  }

  closeModal(): void {
    this.selectedLog = null;
  }

  formatPayload(payload: string): string {
    if (!payload) return 'None';
    try {
      const parsed = JSON.parse(payload);
      return JSON.stringify(parsed, null, 2);
    } catch (e) {
      return payload;
    }
  }

  formatDate(dateStr: string): string {
    if (!dateStr) return '';
    const date = this.parseDate(dateStr);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }) + ' ' + 
           date.toLocaleDateString([], { month: 'short', day: 'numeric' });
  }

  parseDate(dateStr: string): Date {
    const localDate = new Date(dateStr);
    const now = new Date();
    const diffMinutes = (localDate.getTime() - now.getTime()) / 60000;
    
    if (diffMinutes < -30 || diffMinutes > 30) {
      const utcDate = new Date(dateStr + 'Z');
      const utcDiffMinutes = (utcDate.getTime() - now.getTime()) / 60000;
      if (Math.abs(utcDiffMinutes) < Math.abs(diffMinutes)) {
        return utcDate;
      }
    }
    return localDate;
  }

  formatAge(ageSeconds: number): string {
    const mins = Math.floor(ageSeconds / 60);
    const secs = Math.floor(ageSeconds % 60);
    if (mins === 0) return `${secs}s`;
    return `${mins}m ${secs}s`;
  }

  getActionBadgeClass(action: string): string {
    const act = action.toUpperCase();
    if (act.includes('FAILED') || act.includes('EXPIRED') || act.includes('ERROR')) {
      return 'badge-danger';
    }
    if (act.includes('CONFIRMED') || act.includes('SUCCESS') || act.includes('HELD') || act.includes('INITIATED') || act.includes('RESCUED') || act.includes('REFUNDED')) {
      return 'badge-success';
    }
    return 'badge-warning';
  }

  showSuccess(msg: string): void {
    this.successMessage = msg;
    this.triggerAlertTimeout();
  }

  showError(msg: string): void {
    this.errorMessage = msg;
    this.triggerAlertTimeout();
  }

  triggerAlertTimeout(): void {
    if (this.alertTimeout) clearTimeout(this.alertTimeout);
    this.alertTimeout = setTimeout(() => {
      this.successMessage = null;
      this.errorMessage = null;
    }, 5000);
  }
}
