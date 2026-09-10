package com.gertec.flutterprinter.flutter_printer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.util.Log;

import androidx.annotation.NonNull;

import br.com.gertec.gedi.GEDI;
import br.com.gertec.gedi.enums.GEDI_PRNTR_e_Alignment;
import br.com.gertec.gedi.exceptions.GediException;
import br.com.gertec.gedi.interfaces.IGEDI;
import br.com.gertec.gedi.interfaces.IPRNTR;
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_PictureConfig;
import br.com.gertec.gedi.structs.GEDI_PRNTR_st_StringConfig;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * Ponte para a impressora interna de terminais Gertec, via SDK nativo
 * (GEDI — br.com.gertec.gedi.interfaces.IPRNTR), vendorizado em
 * android/app/libs/ a partir de
 * https://github.com/jhonathanqz/gertec_pos_printer/.
 *
 * ATENÇÃO: o .aar vendorizado (libgedi-1.16.8-gpos700-release) foi
 * originalmente empacotado visando o modelo GPOS700; o GPOS780 usado
 * neste projeto é um modelo diferente e pode não ser 100% compatível —
 * validar em hardware real.
 *
 * O GEDI não aceita bytes ESC/POS crus, mas — diferente do PAX — tem
 * primitivos nativos completos: alinhamento real em DrawPictureExt/
 * StringConfig (via android.graphics.Paint, que também dá negrito,
 * itálico, sublinhado e riscado de verdade, sem gambiarra de espaços).
 * Por isso este arquivo interpreta a mesma sequência de bytes ESC/POS que
 * EscPosFormat (Dart) gera para todo mundo e traduz para chamadas GEDI.
 */
public class GertecPrinterManager implements MethodChannel.MethodCallHandler {

    private static final String TAG = "GertecPrinterManager";
    private static final int INIT_TIMEOUT_MS = 3000;
    private static final int INIT_POLL_INTERVAL_MS = 50;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile IPRNTR printer;

    public GertecPrinterManager(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "gertecConnect":
                connect(result);
                break;
            case "gertecDisconnect":
                printer = null;
                result.success(true);
                break;
            case "gertecIsConnected":
                result.success(printer != null);
                break;
            case "gertecPrintBytes":
                List<Integer> bytesArg = call.argument("bytes");
                printBytes(bytesArg, result);
                break;
            default:
                result.notImplemented();
        }
    }

    private void connect(MethodChannel.Result result) {
        executor.execute(() -> {
            try {
                GEDI.init(context);
                IGEDI gedi = GEDI.getInstance(context);

                long deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS;
                IPRNTR p = null;
                while (p == null && System.currentTimeMillis() < deadline) {
                    p = gedi.getPRNTR();
                    if (p == null) {
                        Thread.sleep(INIT_POLL_INTERVAL_MS);
                    }
                }
                if (p == null) {
                    result.error("NO_GERTEC_PRINTER", "Timeout aguardando serviço GEDI.", null);
                    return;
                }
                p.Init();
                printer = p;
                result.success(true);
            } catch (Throwable e) {
                // Throwable de propósito: em aparelhos que não são Gertec, as
                // classes GEDI podem nem carregar (NoClassDefFoundError etc).
                printer = null;
                Log.e(TAG, "Falha ao conectar na impressora interna Gertec", e);
                result.error("NO_GERTEC_PRINTER",
                        e.getClass().getSimpleName() + ": " + e.getMessage(), null);
            }
        });
    }

    private void printBytes(List<Integer> bytesArg, MethodChannel.Result result) {
        IPRNTR currentPrinter = printer;
        if (currentPrinter == null) {
            result.error("NOT_CONNECTED", "Impressora interna Gertec não conectada.", null);
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
        executor.execute(() -> {
            try {
                new EscPosToGertecInterpreter(currentPrinter).run(bytes);
                result.success(true);
            } catch (Throwable e) {
                Log.e(TAG, "Falha ao imprimir na impressora interna Gertec", e);
                result.error("WRITE_FAILED",
                        e.getClass().getSimpleName() + ": " + e.getMessage(), null);
            }
        });
    }

    /** Interpretador ESC/POS -> chamadas nativas GEDI, uma execução por job. */
    private static class EscPosToGertecInterpreter {
        private final IPRNTR printer;
        private GEDI_PRNTR_e_Alignment align = GEDI_PRNTR_e_Alignment.LEFT;
        private boolean bold;
        private boolean italic;
        private boolean underline;
        private boolean strikeThrough;
        private float textSize = 24f;
        private final StringBuilder pendingLine = new StringBuilder();

        EscPosToGertecInterpreter(IPRNTR printer) {
            this.printer = printer;
        }

        void run(byte[] bytes) throws GediException {
            printer.Init();
            int i = 0;
            while (i < bytes.length) {
                int b = bytes[i] & 0xFF;

                if (b == 0x1B && i + 1 < bytes.length) {
                    int cmd = bytes[i + 1] & 0xFF;
                    if (cmd == 0x40) { // ESC @ reset
                        flushLine();
                        align = GEDI_PRNTR_e_Alignment.LEFT;
                        bold = false;
                        italic = false;
                        underline = false;
                        strikeThrough = false;
                        textSize = 24f;
                        i += 2;
                        continue;
                    }
                    if (cmd == 0x61 && i + 2 < bytes.length) { // ESC a n (align)
                        int n = bytes[i + 2] & 0xFF;
                        align = n == 1 ? GEDI_PRNTR_e_Alignment.CENTER
                                : n == 2 ? GEDI_PRNTR_e_Alignment.RIGHT
                                : GEDI_PRNTR_e_Alignment.LEFT;
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x45 && i + 2 < bytes.length) { // ESC E n (negrito)
                        bold = (bytes[i + 2] & 0xFF) != 0;
                        i += 3;
                        continue;
                    }
                    if ((cmd == 0x34 || cmd == 0x35) && i + 1 < bytes.length) { // itálico
                        italic = cmd == 0x34;
                        i += 2;
                        continue;
                    }
                    if (cmd == 0x2D && i + 2 < bytes.length) { // ESC - n (sublinhado)
                        underline = (bytes[i + 2] & 0xFF) != 0;
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x47 && i + 2 < bytes.length) { // ESC G n (riscado)
                        strikeThrough = (bytes[i + 2] & 0xFF) != 0;
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x4D && i + 2 < bytes.length) { // ESC M n (fonte): ignorado
                        i += 3;
                        continue;
                    }
                    i += 2;
                    continue;
                }

                if (b == 0x1D && i + 1 < bytes.length) {
                    int cmd = bytes[i + 1] & 0xFF;
                    if (cmd == 0x21 && i + 2 < bytes.length) { // GS ! n (escala)
                        int n = bytes[i + 2] & 0xFF;
                        textSize = n == 0x22 ? 42f : n == 0x11 ? 32f : 24f;
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x56 && i + 2 < bytes.length) { // GS V n (corte)
                        // Sem primitivo de corte no IPRNTR — ignorado.
                        flushLine();
                        i += 3;
                        continue;
                    }
                    if (cmd == 0x76 && i + 2 < bytes.length && (bytes[i + 2] & 0xFF) == 0x30) {
                        flushLine();
                        i = printRasterBlock(bytes, i);
                        continue;
                    }
                    i += 2;
                    continue;
                }

                if (b == 0x0A) {
                    flushLine();
                    i++;
                    continue;
                }

                pendingLine.append((char) b);
                i++;
            }
            flushLine();
            printer.Output();
        }

        private void flushLine() throws GediException {
            if (pendingLine.length() == 0) {
                printer.DrawBlankLine(1);
                return;
            }
            String text = pendingLine.toString();
            pendingLine.setLength(0);

            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setTextSize(textSize);
            paint.setFakeBoldText(bold);
            paint.setUnderlineText(underline);
            paint.setStrikeThruText(strikeThrough);
            if (italic) paint.setTextSkewX(-0.25f);
            Paint.Align paintAlign;
            if (align == GEDI_PRNTR_e_Alignment.CENTER) {
                paintAlign = Paint.Align.CENTER;
            } else if (align == GEDI_PRNTR_e_Alignment.RIGHT) {
                paintAlign = Paint.Align.RIGHT;
            } else {
                paintAlign = Paint.Align.LEFT;
            }
            paint.setTextAlign(paintAlign);

            GEDI_PRNTR_st_StringConfig stringConfig = new GEDI_PRNTR_st_StringConfig(paint);
            printer.DrawStringExt(stringConfig, text);
        }

        /** Lê um bloco GS v 0 a partir de [start] e chama DrawPictureExt. Retorna o próximo índice. */
        private int printRasterBlock(byte[] bytes, int start) throws GediException {
            int p = start + 3; // pula GS v 0
            p++; // m: sempre 0 no que geramos
            int xL = bytes[p++] & 0xFF;
            int xH = bytes[p++] & 0xFF;
            int yL = bytes[p++] & 0xFF;
            int yH = bytes[p++] & 0xFF;
            int bytesPerRow = xL | (xH << 8);
            int height = yL | (yH << 8);
            int dataLen = bytesPerRow * height;

            Bitmap bitmap = decodeMonoBitmap(bytes, p, bytesPerRow, height);

            GEDI_PRNTR_st_PictureConfig pictureConfig = new GEDI_PRNTR_st_PictureConfig(align);
            printer.DrawPictureExt(pictureConfig, bitmap);

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
    }
}
