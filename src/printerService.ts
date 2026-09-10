import AsyncStorage from '@react-native-async-storage/async-storage';
import {nativePrinter, requestBluetoothPermission} from './nativePrinter';
import type {
  DeviceInfo,
  EffectivePrinterMethod,
  PrinterDevice,
  PrinterMethod,
  PrinterSettings,
} from './types';

const SETTINGS_KEY = '@react-pos-printer/settings-v1';
const defaults: PrinterSettings = {method: 'auto', paperSize: '58mm', devices: {}};

export const methodLabels: Record<PrinterMethod, string> = {
  auto: 'Automático (pela marca)',
  bluetooth: 'Bluetooth clássico (SPP)',
  network: 'Rede — IP:porta (ESC/POS)',
  sunmiInternal: 'Impressora interna SUNMI',
  paxInternal: 'Impressora interna PAX',
  gertecInternal: 'Impressora interna Gertec',
  positivoInternal: 'Impressora interna POSITIVO',
};

export async function loadSettings(): Promise<PrinterSettings> {
  try {
    const value = await AsyncStorage.getItem(SETTINGS_KEY);
    return value ? {...defaults, ...JSON.parse(value)} : defaults;
  } catch {
    return defaults;
  }
}

export const saveSettings = (settings: PrinterSettings) =>
  AsyncStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));

export function recommendedMethod(info: DeviceInfo): EffectivePrinterMethod {
  const value = `${info.manufacturer} ${info.brand} ${info.model}`.toUpperCase();
  if (value.includes('SUNMI')) return 'sunmiInternal';
  if (value.includes('GERTEC') || value.includes('WISEASY')) return 'gertecInternal';
  if (value.includes('POSITIVO') || /\bL[345]00\b/.test(value)) return 'positivoInternal';
  // A integração PAX interna depende muito do firmware; Bluetooth é o caminho validado.
  return 'bluetooth';
}

export async function resolveMethod(method: PrinterMethod): Promise<EffectivePrinterMethod> {
  if (method !== 'auto') return method;
  return recommendedMethod(await nativePrinter.getDeviceInfo());
}

export class PrinterConnection {
  readonly method: EffectivePrinterMethod;
  private device?: PrinterDevice;

  constructor(method: EffectivePrinterMethod) {
    this.method = method;
  }

  async listDevices(): Promise<PrinterDevice[]> {
    if (this.method === 'bluetooth') {
      if (!(await requestBluetoothPermission())) throw new Error('Permissão Bluetooth não concedida.');
      if (!(await nativePrinter.isBluetoothEnabled(this.method))) throw new Error('O Bluetooth está desligado.');
    }
    return nativePrinter.listDevices(this.method);
  }

  async connect(device: PrinterDevice): Promise<boolean> {
    if (this.method === 'bluetooth' && !(await requestBluetoothPermission())) {
      throw new Error('Permissão Bluetooth não concedida.');
    }
    const connected = await nativePrinter.connect(this.method, device.address);
    if (connected) this.device = device;
    return connected;
  }

  async reconnect(device?: PrinterDevice): Promise<boolean> {
    const target = device ?? this.device;
    return target ? this.connect(target) : false;
  }

  isConnected(): Promise<boolean> {
    return nativePrinter.isConnected(this.method);
  }

  async disconnect(): Promise<void> {
    await nativePrinter.disconnect(this.method);
    this.device = undefined;
  }

  printBytes(bytes: number[]): Promise<boolean> {
    if (!bytes.length) throw new Error('A impressão não pode estar vazia.');
    return nativePrinter.printBytes(this.method, bytes.map(value => value & 0xff));
  }
}
