import { Component } from '@angular/core';
import { BillingService } from './billing.service';

/** Liste des factures (composant minimal, sans template réel). */
@Component({ selector: 'app-invoice-list', template: '<ul></ul>' })
export class InvoiceListComponent {
  constructor(readonly billing: BillingService) {}
}
