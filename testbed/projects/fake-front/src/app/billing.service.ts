import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { environment } from '../environments/environment';

/**
 * Appelle l'API de fake-billing via l'URL d'environnement.
 * Arête attendue : project:fakefront -CALLS_API-> project:fakebilling.
 */
@Injectable({ providedIn: 'root' })
export class BillingService {
  constructor(private readonly http: HttpClient) {}

  listInvoices() {
    return this.http.get(`${environment.billingUrl}/api/invoices`);
  }
}
