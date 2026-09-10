import {barcodeCode128, buildTestReceipt, qrCode, textLine} from '../src/escpos';
import {describe, expect, it} from '@jest/globals';

describe('ESC/POS', () => {
  it('gera comandos de texto inicializados corretamente', () => {
    expect(textLine('OK', {align: 1, bold: true})).toEqual(
      expect.arrayContaining([0x1b, 0x61, 1, 0x1d, 0x21, 0, 0x1b, 0x45, 1]),
    );
  });

  it('rasteriza QR e Code 128 em blocos GS v 0', () => {
    expect(qrCode('teste')).toEqual(expect.arrayContaining([0x1d, 0x76, 0x30]));
    expect(barcodeCode128('123456')).toEqual(expect.arrayContaining([0x1d, 0x76, 0x30]));
  });

  it('gera cupons para as duas larguras de papel', () => {
    expect(buildTestReceipt('58mm').length).toBeGreaterThan(1000);
    expect(buildTestReceipt('80mm').length).toBeGreaterThan(1000);
  });
});
