import QRCode from 'qrcode';
import type {PaperSize, ReceiptData} from './types';

export const ESC = 0x1b;
export const GS = 0x1d;
export type Align = 0 | 1 | 2;

const concat = (...parts: number[][]): number[] => parts.flat();
const latin1 = (value: string): number[] =>
  Array.from(value.normalize('NFD').replace(/[\u0300-\u036f]/g, '')).map(char => {
    const code = char.charCodeAt(0);
    return code <= 255 ? code : 0x3f;
  });

export const reset = (): number[] => [ESC, 0x40];
export const align = (value: Align): number[] => [ESC, 0x61, value];
export const bold = (on: boolean): number[] => [ESC, 0x45, on ? 1 : 0];
export const feed = (lines: number): number[] => Array(Math.max(0, Math.min(20, lines))).fill(0x0a);
export const cut = (): number[] => [GS, 0x56, 0x01];
export const text = (value: string): number[] => latin1(value);

export function textLine(
  value: string,
  options: {align?: Align; scale?: 0 | 1 | 2; bold?: boolean; maxScale?: 1 | 2} = {},
): number[] {
  const size = Math.min(options.scale ?? 0, options.maxScale ?? 2);
  return concat(
    align(options.align ?? 0),
    [GS, 0x21, size === 1 ? 0x11 : size === 2 ? 0x22 : 0],
    bold(options.bold ?? false),
    text(value + '\n'),
  );
}

function raster(matrix: boolean[][], alignment: Align = 1, stripeHeight = 48): number[] {
  if (!matrix.length || !matrix[0].length) return [];
  const width = matrix[0].length;
  const bytesPerRow = Math.ceil(width / 8);
  const output = [...align(alignment)];
  for (let top = 0; top < matrix.length; top += stripeHeight) {
    const height = Math.min(stripeHeight, matrix.length - top);
    output.push(GS, 0x76, 0x30, 0, bytesPerRow & 255, bytesPerRow >> 8, height & 255, height >> 8);
    for (let y = 0; y < height; y++) {
      for (let bx = 0; bx < bytesPerRow; bx++) {
        let byte = 0;
        for (let bit = 0; bit < 8; bit++) {
          if (matrix[top + y][bx * 8 + bit]) byte |= 0x80 >> bit;
        }
        output.push(byte);
      }
    }
  }
  return output;
}

export function qrCode(data: string, sizeDots = 232, stripeHeight = 48): number[] {
  const qr = QRCode.create(data, {errorCorrectionLevel: 'M'});
  const count = qr.modules.size;
  const quiet = 4;
  const scale = Math.max(1, Math.floor(sizeDots / (count + quiet * 2)));
  const size = (count + quiet * 2) * scale;
  const matrix = Array.from({length: size}, (_, y) =>
    Array.from({length: size}, (_, x) => {
      const row = Math.floor(y / scale) - quiet;
      const col = Math.floor(x / scale) - quiet;
      return row >= 0 && col >= 0 && row < count && col < count
        ? Boolean(qr.modules.get(row, col))
        : false;
    }),
  );
  return raster(matrix, 1, stripeHeight);
}

// Larguras alternadas barra/espaço para os 107 símbolos Code 128.
const CODE128 = [
  '212222','222122','222221','121223','121322','131222','122213','122312','132212','221213','221312','231212',
  '112232','122132','122231','113222','123122','123221','223211','221132','221231','213212','223112','312131',
  '311222','321122','321221','312212','322112','322211','212123','212321','232121','111323','131123','131321',
  '112313','132113','132311','211313','231113','231311','112133','112331','132131','113123','113321','133121',
  '313121','211331','231131','213113','213311','213131','311123','311321','331121','312113','312311','332111',
  '314111','221411','431111','111224','111422','121124','121421','141122','141221','112214','112412','122114',
  '122411','142112','142211','241211','221114','413111','241112','134111','111242','121142','121241','114212',
  '124112','124211','411212','421112','421211','212141','214121','412121','111143','111341','131141','114113',
  '114311','411113','411311','113141','114131','311141','411131','211412','211214','211232','2331112',
];

export function barcodeCode128(data: string, widthDots = 300, height = 82): number[] {
  const values = Array.from(data).map(char => {
    const code = char.charCodeAt(0);
    return code >= 32 && code <= 126 ? code - 32 : 31;
  });
  const symbols = [104, ...values];
  const checksum = symbols.reduce((sum, value, index) => sum + value * Math.max(1, index), 0) % 103;
  symbols.push(checksum, 106);
  const modules: boolean[] = Array(10).fill(false);
  for (const symbol of symbols) {
    let bar = true;
    for (const width of CODE128[symbol]) {
      modules.push(...Array(Number(width)).fill(bar));
      bar = !bar;
    }
  }
  modules.push(...Array(10).fill(false));
  const scale = Math.max(1, Math.floor(widthDots / modules.length));
  const row = modules.flatMap(value => Array(scale).fill(value));
  return raster(Array.from({length: height}, () => [...row]), 1, 48);
}

export function buildTestReceipt(paper: PaperSize, maxScale: 1 | 2 = 1): number[] {
  const columns = paper === '58mm' ? 32 : 48;
  return concat(
    reset(), [ESC, 0x4d, 0],
    textLine('REACT POS PRINTER', {align: 1, scale: 1, bold: true, maxScale}),
    textLine('Teste ESC/POS', {align: 1}),
    text('-'.repeat(columns) + '\n'),
    textLine('Bluetooth SPP / TCP 9100'),
    textLine(new Date().toLocaleString('pt-BR')),
    text('-'.repeat(columns) + '\n'),
    qrCode('https://reactnative.dev'),
    textLine('7891234567895', {align: 1}),
    barcodeCode128('7891234567895', paper === '58mm' ? 300 : 420),
    feed(4), cut(),
  );
}

export function buildReceipt(receipt: ReceiptData, paper: PaperSize, maxScale: 1 | 2 = 1): number[] {
  const columns = paper === '58mm' ? 32 : 48;
  const money = (value: number) => value.toFixed(2).replace('.', ',');
  const bytes: number[][] = [
    reset(), [ESC, 0x4d, 0],
    textLine(receipt.storeName, {align: 1, scale: 1, bold: true, maxScale}),
    textLine(receipt.storeSubtitle, {align: 1}),
    textLine(receipt.dateTime.toLocaleString('pt-BR'), {align: 1}),
    text('-'.repeat(columns) + '\n'),
  ];
  for (const item of receipt.items) {
    bytes.push(textLine(item.name));
    const left = `${item.quantity} x R$ ${money(item.unitPrice)}`;
    const right = `R$ ${money(item.quantity * item.unitPrice)}`;
    bytes.push(text(left + ' '.repeat(Math.max(1, columns - left.length - right.length)) + right + '\n'));
  }
  const total = receipt.items.reduce((sum, item) => sum + item.quantity * item.unitPrice, 0);
  bytes.push(
    text('-'.repeat(columns) + '\n'),
    textLine(`TOTAL R$ ${money(total)}`, {align: 2, scale: 1, bold: true, maxScale}),
    qrCode(receipt.qrPayload),
    textLine(receipt.barcodePayload, {align: 1}),
    barcodeCode128(receipt.barcodePayload, paper === '58mm' ? 300 : 420),
    feed(4), cut(),
  );
  return concat(...bytes);
}
