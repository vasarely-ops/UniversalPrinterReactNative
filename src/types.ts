export type PrinterMethod =
  | 'auto'
  | 'bluetooth'
  | 'network'
  | 'sunmiInternal'
  | 'paxInternal'
  | 'gertecInternal'
  | 'positivoInternal';

export type EffectivePrinterMethod = Exclude<PrinterMethod, 'auto'>;
export type PaperSize = '58mm' | '80mm';

export interface PrinterDevice {
  name: string;
  address: string;
}

export interface DeviceInfo {
  manufacturer: string;
  brand: string;
  model: string;
}

export interface PrinterSettings {
  method: PrinterMethod;
  paperSize: PaperSize;
  devices: Partial<Record<EffectivePrinterMethod, PrinterDevice>>;
}

export interface ReceiptItem {
  name: string;
  quantity: number;
  unitPrice: number;
}

export interface ReceiptData {
  storeName: string;
  storeSubtitle: string;
  dateTime: Date;
  items: ReceiptItem[];
  qrPayload: string;
  barcodePayload: string;
}
