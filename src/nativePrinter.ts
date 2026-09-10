import {NativeModules, PermissionsAndroid, Platform} from 'react-native';
import type {DeviceInfo, EffectivePrinterMethod, PrinterDevice} from './types';

interface POSPrinterModule {
  getDeviceInfo(): Promise<DeviceInfo>;
  hasPermission(method: string): Promise<boolean>;
  isBluetoothEnabled(method: string): Promise<boolean>;
  listDevices(method: string): Promise<PrinterDevice[]>;
  connect(method: string, address: string): Promise<boolean>;
  disconnect(method: string): Promise<boolean>;
  isConnected(method: string): Promise<boolean>;
  printBytes(method: string, bytes: number[]): Promise<boolean>;
}

const native = NativeModules.POSPrinter as POSPrinterModule | undefined;

function module(): POSPrinterModule {
  if (!native) {
    throw new Error('Módulo POSPrinter indisponível. Execute o app em um aparelho Android.');
  }
  return native;
}

export async function requestBluetoothPermission(): Promise<boolean> {
  if (Platform.OS !== 'android' || Number(Platform.Version) < 31) return true;
  const result = await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
    {
      title: 'Permissão para a impressora',
      message: 'O app precisa acessar os dispositivos Bluetooth pareados para imprimir.',
      buttonPositive: 'Permitir',
      buttonNegative: 'Cancelar',
    },
  );
  return result === PermissionsAndroid.RESULTS.GRANTED;
}

export const nativePrinter = {
  getDeviceInfo: () => module().getDeviceInfo(),
  hasPermission: (method: EffectivePrinterMethod) => module().hasPermission(method),
  isBluetoothEnabled: (method: EffectivePrinterMethod) => module().isBluetoothEnabled(method),
  listDevices: (method: EffectivePrinterMethod) => module().listDevices(method),
  connect: (method: EffectivePrinterMethod, address: string) => module().connect(method, address),
  disconnect: (method: EffectivePrinterMethod) => module().disconnect(method),
  isConnected: (method: EffectivePrinterMethod) => module().isConnected(method),
  printBytes: (method: EffectivePrinterMethod, bytes: number[]) => module().printBytes(method, bytes),
};
