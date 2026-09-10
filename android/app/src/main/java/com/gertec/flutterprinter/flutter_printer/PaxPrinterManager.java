package com.gertec.flutterprinter.flutter_printer;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.pax.dal.IDAL;
import com.pax.dal.IPrinter;
import com.pax.dal.exceptions.PrinterDevException;
import com.pax.neptunelite.api.NeptuneLiteUser;

import java.util.List;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Ponte para a impressora interna de terminais PAX, via SDK nativo
 * (IDAL/Neptune — com.pax.dal.IPrinter), vendorizado em android/app/libs/.
 *
 * Diferente da SUNMI, o IPrinter do PAX NÃO aceita bytes ESC/POS crus nem
 * tem um primitivo de alinhamento (confirmado tanto na API oficial quanto
 * em wrappers de terceiros, que simulam alinhamento preenchendo a string
 * com espaços). Por isso este arquivo faz um pequeno "interpretador": lê
 * a mesma sequência de bytes ESC/POS que EscPosFormat (Dart) gera para
 * todo mundo, e traduz para chamadas nativas do PAX:
 *  - alinhamento de texto -> padding com espaços (igual a wrappers PAX
 *    conhecidos fazem, na ausência de um comando nativo)
 *  - escala de fonte -> doubleWidth()/doubleHeight() reais da API
 *  - negrito/itálico/sublinhado/riscado -> sem primitivo equivalente no
 *    IPrinter; ignorados silenciosamente (mesma politica do restante do
 *    app para estilos sem suporte garantido de hardware)
 *  - blocos raster (QR, código de barras, imagem) -> reconstrói o bitmap
 *    a partir dos bits recebidos e usa printBitmap(), posicionando a
 *    imagem dentro da largura do papel conforme o alinhamento atual
 */
public class PaxPrinterManager implements MethodChannel.MethodCallHandler {

    private static final String TAG = "PaxPrinterManager";
    private static final int PAPER_DOTS_WIDTH = 384; // 58mm, ver PrinterPaperSize (Dart)
    private static final int NORMAL_CHARS_PER_LINE = 32;
    private static final int DOUBLE_CHARS_PER_LINE = 16;

    private final Context context;
    private volatile IPrinter printer;

    public PaxPrinterManager(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "paxConnect":
                connect(result);
                break;
            case "paxDisconnect":
                printer = null;
                result.success(true);
                break;
            case "paxIsConnected":
                result.success(printer != null);
                break;
            case "paxPrintBytes":
                List<Integer> bytesArg = call.argument("bytes");
                printBytes(bytesArg, result);
                break;
            default:
                result.notImplemented();
        }
    }

    private void connect(MethodChannel.Result result) {
        try {
            IDAL dal = NeptuneLiteUser.getInstance().getDal(context);
            IPrinter p = dal.getPrinter();
            p.init();
            printer = p;
            result.success(true);
        } catch (Throwable e) {
            // Throwable de propósito: em aparelhos que não são PAX, a classe
            // NeptuneLiteUser normalmente nem carrega (UnsatisfiedLinkError /
            // NoClassDefFoundError), não só as exceptions declaradas do SDK.
            printer = null;
            Log.e(TAG, "Falha ao conectar na impressora interna PAX", e);
            result.error("NO_PAX_PRINTER", e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private void printBytes(List<Integer> bytesArg, MethodChannel.Result result) {
        IPrinter currentPrinter = printer;
        if (currentPrinter == null) {
            result.error("NOT_CONNECTED", "Impressora interna PAX não conectada.", null);
            return;
        }
        if (bytesArg == null) {
            result.error("INVALID_ARGUMENT", "Nenhum dado informado para impressão.", null);
            return;
        }
        byte[] bytes = new byte[bytesArg.size()];
        for (int i = 0; i < bytesArg.size(); i++) {
            bytes[i] = (byte) (int) bytesArg.get(i);
        }
        try {
            new EscPosToPaxInterpreter(currentPrinter).run(bytes);
            result.success(true);
        } catch (Throwable e) {
            Log.e(TAG, "Falha ao imprimir na impressora interna PAX", e);
            result.error("WRITE_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    /** Interpretador ESC/POS -> chamadas nativas IPrinter, uma execução por job. */
    private static class EscPosToPaxInterpreter {
        private final IPrinter printer;
        private int align = 0; // 0 esquerda, 1 centro, 2 direita
        private boolean doubleSize = false;
        private final StringBuilder pendingLine = new StringBuilder();

        EscPosToPaxInterpreter(IPrinter printer) {
            this.printer = printer;
        }

        void run(byte[] bytes) throws PrinterDevException {
            int i = 0;
            while (i < bytes.length) {
                int b = bytes[i] & 0xFF;

                if (b == 0x1B && i + 1 < bytes.length) {
                    int cmd = bytes[i + 1] & 0xFF;
                    if (cmd == 0x40) { // ESC @ reset
                        flushLine();
                        align = 0;
                        doubleSize = false;
                        i += 2;
                        continue;
                    }
                    if (cmd == 0x61 && i + 2 < bytes.length) { // ESC a n (align)
                        align = bytes[i + 2] & 0xFF;
                        i += 3;
                        continue;
                    }
                    if ((cmd == 0x45 || cmd == 0x34 || cmd == 0x35 || cmd == 0x47)
                            && i + 2 < bytes.length) {
                        // ESC E (negrito), ESC 4/5 (itálico), ESC G (riscado):
                        // sem primitivo no IPrinter, ignorados.
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x2D && i + 2 < bytes.length) { // ESC - n (sublinhado)
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x4D && i + 2 < bytes.length) { // ESC M n (fonte)
                        i += 3;
                        continue;
                    }
                    // Comando ESC não reconhecido: pula só o opcode.
                    i += 2;
                    continue;
                }

                if (b == 0x1D && i + 1 < bytes.length) {
                    int cmd = bytes[i + 1] & 0xFF;
                    if (cmd == 0x21 && i + 2 < bytes.length) { // GS ! n (escala)
                        int n = bytes[i + 2] & 0xFF;
                        doubleSize = n != 0x00;
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x56 && i + 2 < bytes.length) { // GS V n (corte)
                        flushLine();
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x76 && i + 2 < bytes.length && (bytes[i + 2] & 0xFF) == 0x30) {
                        // GS v 0 m xL xH yL yH <dados>: bloco raster
                        flushLine();
                        i = printRasterBlock(bytes, i);
                        continue;
                    }
                    // Comando GS não reconhecido: pula só o opcode.
                    i += 2;
                    continue;
                }

                if (b == 0x0A) { // \n
                    flushLine();
                    i++;
                    continue;
                }

                pendingLine.append((char) b);
                i++;
            }
            flushLine();
        }

        private void flushLine() throws PrinterDevException {
            if (pendingLine.length() == 0) {
                printer.printStr("\n", null);
                return;
            }
            String text = pendingLine.toString();
            pendingLine.setLength(0);

            int charsPerLine = doubleSize ? DOUBLE_CHARS_PER_LINE : NORMAL_CHARS_PER_LINE;
            String padded = applyAlignment(text, align, charsPerLine);

            printer.doubleWidth(doubleSize, doubleSize);
            printer.doubleHeight(doubleSize, doubleSize);
            printer.printStr(padded + "\n", null);
        }

        /** Alinhamento "por software": preenche com espaços à esquerda. */
        private static String applyAlignment(String text, int align, int charsPerLine) {
            if (align == 0 || text.length() >= charsPerLine) {
                return text;
            }
            int freeSpace = charsPerLine - text.length();
            int leftPad = align == 1 ? freeSpace / 2 : freeSpace; // 1=centro, 2=direita
            StringBuilder sb = new StringBuilder(charsPerLine);
            for (int p = 0; p < leftPad; p++) sb.append(' ');
            sb.append(text);
            return sb.toString();
        }

        /** Lê um bloco GS v 0 a partir de [start] e chama printBitmap. Retorna o próximo índice. */
        private int printRasterBlock(byte[] bytes, int start) throws PrinterDevException {
            int p = start + 3; // pula GS v 0
            int m = bytes[p++] & 0xFF; // sempre 0 no que geramos
            int xL = bytes[p++] & 0xFF;
            int xH = bytes[p++] & 0xFF;
            int yL = bytes[p++] & 0xFF;
            int yH = bytes[p++] & 0xFF;
            int bytesPerRow = xL | (xH << 8);
            int height = yL | (yH << 8);
            int dataLen = bytesPerRow * height;

            Bitmap content = decodeMonoBitmap(bytes, p, bytesPerRow, height);
            Bitmap positioned = positionOnPaper(content, align);
            printer.printBitmap(positioned);

            return p + dataLen;
        }

        private static Bitmap decodeMonoBitmap(byte[] bytes, int offset, int bytesPerRow, int height) {
            int width = bytesPerRow * 8;
            int[] pixels = new int[width * height];
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int byteIndex = offset + row * bytesPerRow + (col / 8);
                    int bitIndex = 7 - (col % 8);
                    boolean black = byteIndex < bytes.length
                            && ((bytes[byteIndex] >> bitIndex) & 1) == 1;
                    pixels[row * width + col] = black ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        }

        /** Coloca [content] dentro de um bitmap com a largura total do papel, alinhado. */
        private static Bitmap positionOnPaper(Bitmap content, int align) {
            if (content.getWidth() >= PAPER_DOTS_WIDTH) {
                return content;
            }
            Bitmap canvasBmp = Bitmap.createBitmap(
                    PAPER_DOTS_WIDTH, content.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(canvasBmp);
            canvas.drawColor(0xFFFFFFFF);
            int freeSpace = PAPER_DOTS_WIDTH - content.getWidth();
            int left = align == 1 ? freeSpace / 2 : (align == 2 ? freeSpace : 0);
            canvas.drawBitmap(content, left, 0, null);
            return canvasBmp;
        }
    }
}
