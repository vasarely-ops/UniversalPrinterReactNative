# React POS Printer

Conversão do projeto `FlutterPOSPrinter` para React Native Android, com uma API única para impressão ESC/POS por:

- Bluetooth clássico SPP (`BluetoothSocket`);
- rede TCP RAW, porta 9100, com descoberta da sub-rede `/24`;
- impressora interna SUNMI (`sendRAWData`);
- impressora interna PAX (SDK IDAL/Neptune + interpretador ESC/POS);
- impressora interna Gertec/Wiseasy (SDK GEDI + interpretador ESC/POS);
- impressora interna POSITIVO L300/L400/L500 (AIDL `IPrinterService`).

O app usa React Native 0.75.5, última versão com suporte oficial ao Android 6/API 23. O projeto Android está configurado com `minSdkVersion 23`.

## Executar

Requisitos: Node.js 18+, Android SDK e JDK 17 ou 21. JDK 23 não é compatível com o Gradle 8.8 deste projeto.

```powershell
cd J:\ReactPrinter\ReactPOSPrinter
npm install
npm start
```

Em outro terminal, com um aparelho Android conectado:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
npm run android
```

## Gerar APK

```powershell
cd J:\ReactPrinter\ReactPOSPrinter\android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Saídas:

- `android/app/build/outputs/apk/debug/app-debug.apk`
- `android/app/build/outputs/apk/release/app-release.apk`

O build `release` está assinado com a chave de debug apenas para instalação e homologação. Antes de publicar, crie um keystore próprio e altere `signingConfigs.release` em `android/app/build.gradle`.

## API TypeScript

Os exports reutilizáveis estão em `src/index.ts`.

```ts
import {PrinterConnection, textLine, feed} from './src';

const printer = new PrinterConnection('bluetooth');
const devices = await printer.listDevices();

await printer.connect(devices[0]);
await printer.printBytes([
  ...textLine('Pedido #123', {align: 1, bold: true}),
  ...textLine('Pagamento aprovado'),
  ...feed(3),
]);
await printer.disconnect();
```

Para rede:

```ts
const printer = new PrinterConnection('network');
await printer.connect({name: 'Cozinha', address: '192.168.0.50:9100'});
await printer.printBytes(buildTestReceipt('80mm'));
```

Também estão disponíveis `qrCode`, `barcodeCode128`, `buildReceipt` e `buildTestReceipt`.

## Estrutura principal

- `App.tsx`: tela de configuração, conexão e testes;
- `src/printerService.ts`: API de alto nível, persistência e seleção automática por fabricante;
- `src/nativePrinter.ts`: contrato com o módulo nativo Android;
- `src/escpos.ts`: geração de bytes ESC/POS, QR raster e Code 128 raster;
- `android/app/src/main/java/com/reactposprinter/PrinterModule.java`: ponte React Native;
- `android/app/src/main/java/com/gertec/flutterprinter/flutter_printer/`: transportes e drivers dos fabricantes;
- `android/app/src/main/aidl/`: contrato da impressora POSITIVO;
- `android/app/libs/`: SDKs PAX e Gertec migrados do projeto Flutter.

## Observações de hardware

- Pareie a impressora Bluetooth nas configurações do Android antes de abrir a lista no app.
- A descoberta de rede procura a porta 9100 na mesma sub-rede do aparelho. Também é possível informar `IP:porta` manualmente.
- Em terminais PAX, Bluetooth é a escolha automática porque o SDK interno depende da combinação de modelo e firmware. O método PAX interno continua disponível para homologação.
- QR e código de barras são enviados como raster `GS v 0`, evitando diferenças de firmware entre fabricantes.
