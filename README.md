# Universal Printer React Native

Aplicativo e biblioteca de referência para impressão térmica em Android usando React Native. O projeto oferece uma API TypeScript única para impressoras ESC/POS Bluetooth, impressoras de rede e impressoras internas de terminais POS.

O pacote Android gerado é `com.reactposprinter`.

## Recursos

- Bluetooth clássico SPP por `BluetoothSocket`;
- impressão TCP RAW por IP, usando a porta ESC/POS 9100;
- descoberta de impressoras na sub-rede IPv4 local;
- impressora interna SUNMI pela API `sendRAWData`;
- impressora interna PAX pelos SDKs IDAL/Neptune;
- impressora interna Gertec/Wiseasy pelo SDK GEDI;
- impressora interna POSITIVO L300/L400/L500 por AIDL;
- escolha automática do transporte conforme o fabricante do terminal;
- papel térmico de 58 mm e 80 mm;
- texto, alinhamento, negrito, escala, QR Code e Code 128;
- QR Code e código de barras rasterizados para maior compatibilidade;
- persistência da configuração e da última impressora utilizada;
- interface pronta para descoberta, conexão e testes de impressão;
- API reutilizável por outras telas e fluxos do aplicativo.

## Compatibilidade

| Componente | Versão ou requisito |
| --- | --- |
| React Native | 0.75.5 |
| React | 18.3.1 |
| Android mínimo | Android 6.0, API 23 |
| Android de compilação | API 34 |
| Android alvo | API 34 |
| Node.js | 18 ou superior |
| Java | JDK 17 ou 21 |
| Arquitetura React Native | Legada, `newArchEnabled=false` |

O React Native 0.75 foi escolhido porque é a última linha com suporte ao Android 6/API 23, ainda comum em terminais POS. O JDK 23 não deve ser usado com o Gradle 8.8 deste projeto.

## Transportes disponíveis

| Método | Identificador | Descoberta | Formato enviado | Observação |
| --- | --- | --- | --- | --- |
| Automático | `auto` | Conforme o método detectado | Conforme o terminal | Recomendado como padrão |
| Bluetooth SPP | `bluetooth` | Dispositivos já pareados | ESC/POS bruto | Funciona com impressoras térmicas Bluetooth clássicas |
| Rede TCP | `network` | Varredura `/24` na porta 9100 | ESC/POS bruto | Aceita também endereço manual |
| SUNMI interna | `sunmiInternal` | Dispositivo virtual interno | ESC/POS bruto | Usa `com.sunmi:printerlibrary` |
| PAX interna | `paxInternal` | Dispositivo virtual interno | Interpretado para IDAL | Pode variar conforme modelo e firmware |
| Gertec interna | `gertecInternal` | Dispositivo virtual interno | Interpretado para GEDI | SDK incluído para GPOS700 |
| POSITIVO interna | `positivoInternal` | Dispositivo virtual interno | ESC/POS bruto | Usa o serviço `com.xcheng.printerservice` |

No modo automático:

- SUNMI seleciona `sunmiInternal`;
- Gertec ou Wiseasy seleciona `gertecInternal`;
- POSITIVO e modelos L300/L400/L500 selecionam `positivoInternal`;
- PAX e aparelhos genéricos usam `bluetooth` por segurança.

O método PAX interno permanece disponível manualmente. Bluetooth é usado automaticamente em PAX porque a disponibilidade da API interna depende da combinação entre SDK, modelo e firmware.

## Arquitetura

```text
App.tsx
  |
  +-- PrinterConnection (TypeScript)
  |     |
  |     +-- permissões Bluetooth
  |     +-- resolução automática por fabricante
  |     +-- persistência com AsyncStorage
  |     +-- NativeModules.POSPrinter
  |
  +-- escpos.ts
        |
        +-- texto e formatação ESC/POS
        +-- QR Code rasterizado
        +-- Code 128 rasterizado
        +-- cupom de exemplo

NativeModules.POSPrinter
  |
  +-- BluetoothPrinterManager -> BluetoothSocket/SPP
  +-- NetworkPrinterManager   -> Socket TCP/9100
  +-- SunmiPrinterManager     -> SUNMI sendRAWData
  +-- PaxPrinterManager       -> PAX IDAL/Neptune
  +-- GertecPrinterManager    -> Gertec GEDI
  +-- PositivoPrinterManager  -> AIDL IPrinterService
```

Os arquivos `io/flutter/plugin/common/MethodCall.java` e `MethodChannel.java` são apenas uma pequena camada de compatibilidade para reaproveitar os drivers Java já validados no projeto Flutter. O APK React Native não incorpora nem inicializa o runtime Flutter.

## Estrutura do projeto

```text
ReactPOSPrinter/
├── App.tsx
├── src/
│   ├── index.ts
│   ├── types.ts
│   ├── nativePrinter.ts
│   ├── printerService.ts
│   └── escpos.ts
├── __tests__/
├── android/
│   └── app/
│       ├── libs/
│       └── src/main/
│           ├── aidl/
│           ├── AndroidManifest.xml
│           └── java/
└── ios/
```

Arquivos principais:

- `App.tsx`: interface de configuração, conexão e testes;
- `src/types.ts`: tipos públicos de impressora, papel, configuração e cupom;
- `src/printerService.ts`: API de alto nível usada pelo aplicativo;
- `src/nativePrinter.ts`: contrato entre TypeScript e Android;
- `src/escpos.ts`: criação dos bytes de impressão;
- `PrinterModule.java`: módulo nativo exposto como `NativeModules.POSPrinter`;
- `PrinterPackage.java`: registro do módulo no React Native;
- `android/app/libs`: SDKs proprietários necessários para PAX e Gertec;
- `android/app/src/main/aidl`: contratos usados nos terminais POSITIVO.

## Preparação do ambiente

### 1. Pré-requisitos

Instale:

- Node.js 18 ou superior;
- Android Studio e Android SDK 34;
- Android SDK Platform Tools, para acesso ao `adb`;
- JDK 17 ou JDK 21.

No Windows, o Java que acompanha o Android Studio pode ser utilizado:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
```

A localização pode ser diferente conforme a instalação do Android Studio.

### 2. Clonar e instalar

```powershell
git clone https://github.com/vasarely-ops/UniversalPrinterReactNative.git
cd UniversalPrinterReactNative
npm install
```

O repositório é privado. A conta usada no clone precisa ter acesso à organização `vasarely-ops`.

### 3. Iniciar em desenvolvimento

Terminal 1:

```powershell
npm start
```

Terminal 2:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
npm run android
```

Verifique antes se o aparelho está autorizado:

```powershell
adb devices -l
```

## Uso da interface

1. Abra o aplicativo no terminal Android.
2. Selecione `Automático` ou um transporte específico.
3. Escolha papel de 58 mm ou 80 mm.
4. Para Bluetooth, pareie a impressora nas configurações do Android.
5. Toque em `Atualizar lista`.
6. Toque na impressora encontrada para conectar.
7. Para rede, informe `IP:porta` ou use `Buscar na rede`.
8. Execute um dos testes disponíveis.
9. Use `Desconectar` antes de trocar de impressora quando necessário.

Impressoras internas aparecem como `Impressora interna do terminal`, com endereço virtual `internal`.

## API TypeScript

Os componentes públicos são exportados por `src/index.ts`.

### Tipos fundamentais

```ts
type PrinterMethod =
  | 'auto'
  | 'bluetooth'
  | 'network'
  | 'sunmiInternal'
  | 'paxInternal'
  | 'gertecInternal'
  | 'positivoInternal';

type EffectivePrinterMethod = Exclude<PrinterMethod, 'auto'>;
type PaperSize = '58mm' | '80mm';

interface PrinterDevice {
  name: string;
  address: string;
}
```

`auto` precisa ser resolvido antes de criar uma conexão. Para isso, use `resolveMethod`.

### Bluetooth

```ts
import {PrinterConnection, feed, textLine} from './src';

async function imprimirBluetooth() {
  const printer = new PrinterConnection('bluetooth');
  const devices = await printer.listDevices();

  if (devices.length === 0) {
    throw new Error('Nenhuma impressora Bluetooth pareada.');
  }

  const connected = await printer.connect(devices[0]);
  if (!connected) {
    throw new Error('Não foi possível conectar à impressora.');
  }

  await printer.printBytes([
    ...textLine('PEDIDO #123', {align: 1, scale: 1, bold: true}),
    ...textLine('Pagamento aprovado'),
    ...feed(3),
  ]);

  await printer.disconnect();
}
```

Em Android 12 ou superior, `listDevices` e `connect` solicitam automaticamente a permissão `BLUETOOTH_CONNECT`.

### Rede TCP/9100

```ts
import {PrinterConnection, buildTestReceipt} from './src';

async function imprimirNaRede() {
  const printer = new PrinterConnection('network');

  await printer.connect({
    name: 'Impressora da cozinha',
    address: '192.168.0.50:9100',
  });

  await printer.printBytes(buildTestReceipt('80mm'));
  await printer.disconnect();
}
```

Quando a porta é omitida na interface, o aplicativo acrescenta `9100`. Na API, prefira sempre fornecer `host:porta` explicitamente.

### Método automático

```ts
import {PrinterConnection, resolveMethod} from './src';

const method = await resolveMethod('auto');
const printer = new PrinterConnection(method);
const devices = await printer.listDevices();
```

`resolveMethod` consulta `Build.MANUFACTURER`, `Build.BRAND` e `Build.MODEL` pelo módulo Android.

### Impressora interna

```ts
import {PrinterConnection, buildTestReceipt} from './src';

const printer = new PrinterConnection('sunmiInternal');
const [internalPrinter] = await printer.listDevices();

await printer.connect(internalPrinter);
await printer.printBytes(buildTestReceipt('58mm'));
```

Substitua o método por `paxInternal`, `gertecInternal` ou `positivoInternal` conforme o terminal.

### Conteúdo recebido de uma API REST

A impressão é independente da origem dos dados. Um pedido recebido de uma API pode ser convertido para `ReceiptData` e impresso normalmente:

```ts
import {PrinterConnection, ReceiptData, buildReceipt} from './src';

async function imprimirPedido(id: string) {
  const response = await fetch(`https://api.exemplo.com/pedidos/${id}`);
  if (!response.ok) {
    throw new Error(`Falha ao consultar pedido: HTTP ${response.status}`);
  }

  const payload = await response.json();
  const receipt: ReceiptData = {
    storeName: payload.loja.nome,
    storeSubtitle: payload.loja.cnpj,
    dateTime: new Date(payload.criadoEm),
    items: payload.itens.map((item: any) => ({
      name: item.descricao,
      quantity: item.quantidade,
      unitPrice: item.valorUnitario,
    })),
    qrPayload: payload.urlFiscal,
    barcodePayload: payload.codigoBarras,
  };

  const printer = new PrinterConnection('network');
  await printer.connect({name: 'Caixa', address: '192.168.0.50:9100'});
  await printer.printBytes(buildReceipt(receipt, '80mm'));
}
```

Em produção, valide o formato da resposta antes de montar o cupom e use `try/finally` para desconectar quando a conexão não for reutilizada.

## Referência de `PrinterConnection`

### `new PrinterConnection(method)`

Cria uma conexão para um método efetivo. Não passe `auto` diretamente.

### `listDevices(): Promise<PrinterDevice[]>`

- Bluetooth: retorna dispositivos já pareados;
- rede: procura hosts com a porta 9100 aberta;
- interna: retorna um dispositivo virtual.

### `connect(device): Promise<boolean>`

Abre o socket ou conecta ao serviço interno. O retorno `true` confirma que o transporte foi aberto, mas não garante que papel e estado mecânico estejam corretos.

### `reconnect(device?): Promise<boolean>`

Reconecta ao dispositivo informado ou ao último dispositivo usado pela instância.

### `isConnected(): Promise<boolean>`

Consulta o estado do socket ou serviço nativo.

### `printBytes(bytes): Promise<boolean>`

Envia bytes de 0 a 255. Rejeita uma lista vazia. A Promise pode ser rejeitada por desconexão, permissão, serviço ausente ou erro de escrita.

### `disconnect(): Promise<void>`

Fecha a conexão e libera o dispositivo mantido pela instância.

## Referência ESC/POS

### Texto

```ts
textLine('Produto', {
  align: 0,       // 0 esquerda, 1 centro, 2 direita
  scale: 0,       // 0 normal, 1 duplo, 2 triplo
  bold: false,
  maxScale: 1,    // limita a escala para impressoras conservadoras
});
```

Funções disponíveis:

- `reset()` — inicializa o estado ESC/POS;
- `align(0 | 1 | 2)` — define alinhamento;
- `bold(boolean)` — ativa ou desativa negrito;
- `text(string)` — converte texto para bytes;
- `textLine(string, options)` — gera uma linha formatada;
- `feed(lines)` — avança o papel;
- `cut()` — solicita corte parcial;
- `qrCode(data, sizeDots?, stripeHeight?)` — gera QR raster;
- `barcodeCode128(data, widthDots?, height?)` — gera Code 128 raster;
- `buildTestReceipt(paperSize)` — gera o teste completo;
- `buildReceipt(receipt, paperSize)` — gera um cupom comercial.

O texto é normalizado para caracteres compatíveis com Latin-1 básico. Caracteres que não podem ser representados são substituídos. Para layouts que dependam de fontes ou Unicode exato, renderize o conteúdo como bitmap antes de enviá-lo.

### Cupom completo

```ts
import {ReceiptData, buildReceipt} from './src';

const receipt: ReceiptData = {
  storeName: 'MINHA LOJA',
  storeSubtitle: 'CNPJ 12.345.678/0001-90',
  dateTime: new Date(),
  items: [
    {name: 'CAFE 500G', quantity: 2, unitPrice: 18.9},
    {name: 'LEITE 1L', quantity: 3, unitPrice: 5.49},
  ],
  qrPayload: 'https://exemplo.com/documento/123',
  barcodePayload: '7891234567895',
};

const bytes = buildReceipt(receipt, '58mm');
await printer.printBytes(bytes);
```

## Por que QR e código de barras são imagens?

Impressoras ESC/POS de fabricantes diferentes não implementam de maneira uniforme os comandos nativos de QR e barcode. Alguns terminais PAX ignoram esses comandos. O projeto gera a matriz em TypeScript e a envia em blocos raster `GS v 0`.

As imagens são divididas em faixas para não ultrapassar buffers pequenos. Essa abordagem aumenta a compatibilidade entre Bluetooth, rede e APIs internas.

## Persistência

`loadSettings` e `saveSettings` utilizam AsyncStorage com a chave `@react-pos-printer/settings-v1`.

```ts
import {loadSettings, saveSettings} from './src';

const settings = await loadSettings();

await saveSettings({
  ...settings,
  method: 'bluetooth',
  paperSize: '58mm',
});
```

As configurações armazenam:

- método escolhido pelo usuário;
- largura do papel;
- última impressora utilizada em cada transporte.

## Permissões Android

O manifesto declara:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="com.pax.permission.PRINTER" />
```

No Android 12/API 31 ou superior, `BLUETOOTH_CONNECT` é uma permissão solicitada durante a execução. Em versões anteriores, `BLUETOOTH` e `BLUETOOTH_ADMIN` são concedidas na instalação.

O manifesto também declara consultas aos serviços SUNMI e POSITIVO, necessárias devido às regras de visibilidade de pacotes do Android 11 ou superior.

## SDKs dos fabricantes

Os seguintes binários estão versionados em `android/app/libs`:

- `neptune-lite-api-v3.26.00-20210903.jar`;
- `pax-sdk.jar`;
- `libgedi-1.16.8-gpos700-release.aar`;
- `gertec-T1_V2_20230804.jar`.

A biblioteca SUNMI é obtida do Maven:

```gradle
implementation "com.sunmi:printerlibrary:1.0.24"
```

Ao atualizar qualquer SDK, teste em hardware real. APIs proprietárias podem mudar entre modelos e versões de firmware mesmo quando o código compila.

## Build e APK

### Debug

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat assembleDebug
```

Saída:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

### Release para homologação

```powershell
cd android
$env:JAVA_HOME='C:\Program Files\Android\Android Studio1\jbr'
.\gradlew.bat assembleRelease
```

Saída:

```text
android/app/build/outputs/apk/release/app-release.apk
```

A configuração atual assina o release com a chave de debug para facilitar a homologação. Não publique esse APK em uma loja.

### Assinatura de produção

1. Gere um keystore de upload seguro.
2. Guarde o arquivo fora do repositório.
3. Configure as propriedades do keystore localmente ou no cofre de secrets do CI.
4. Crie `signingConfigs.release` em `android/app/build.gradle`.
5. Substitua `signingConfig signingConfigs.debug` por `signingConfigs.release`.
6. Gere o AAB com `gradlew bundleRelease`.

Arquivos `*.keystore`, `*.apk` e `*.aab` estão ignorados pelo Git.

## Instalação por ADB

```powershell
adb devices -l
adb install -r android\app\build\outputs\apk\release\app-release.apk
adb shell monkey -p com.reactposprinter -c android.intent.category.LAUNCHER 1
```

Para remover o aplicativo:

```powershell
adb uninstall com.reactposprinter
```

## Qualidade e testes

```powershell
npx tsc --noEmit
npm run lint
npm test -- --runInBand
```

Os testes atuais verificam a renderização da interface e a geração dos principais comandos ESC/POS, incluindo os blocos raster de QR e Code 128.

## Diagnóstico

### O app fecha ou apresenta erro

Limpe o log, reproduza o problema e consulte as exceções:

```powershell
adb logcat -c
adb shell monkey -p com.reactposprinter -c android.intent.category.LAUNCHER 1
adb logcat -v threadtime AndroidRuntime:E ReactNativeJS:E ReactNative:E '*:S'
```

Para confirmar se o processo continua ativo:

```powershell
adb shell pidof com.reactposprinter
```

### Nenhuma impressora Bluetooth aparece

- confirme que a impressora foi pareada nas configurações do Android;
- confirme que o Bluetooth está ligado;
- aceite a permissão solicitada no Android 12 ou superior;
- alguns dispositivos BLE não oferecem o perfil clássico SPP e não são compatíveis com este transporte;
- reinicie a impressora e atualize a lista.

### A conexão Bluetooth falha

- confira se outro aplicativo está conectado à impressora;
- desligue e ligue novamente o Bluetooth;
- remova o pareamento e pareie novamente;
- confirme que o equipamento oferece o UUID SPP `00001101-0000-1000-8000-00805F9B34FB`.

### A impressora de rede não aparece

- aparelho e impressora devem estar na mesma rede local;
- confirme que a impressora aceita RAW/JetDirect na porta 9100;
- redes com isolamento de clientes impedem a descoberta;
- VPNs podem fazer o app escolher outra interface IPv4;
- use o endereço manual se a descoberta estiver bloqueada;
- teste a porta a partir de outro equipamento da mesma rede.

### A API interna não conecta

- confirme fabricante e modelo do terminal;
- confira se o serviço de impressão do fabricante está instalado e ativo;
- valide a versão do firmware contra a versão do SDK incluído;
- em PAX, tente `bluetooth` antes de concluir que a impressora está indisponível;
- consulte o `adb logcat` procurando por `SunmiPrinterManager`, `PaxPrinterManager`, `GertecPrinterManager` ou `PositivoPrinterManager`.

### Imprime texto, mas não imprime QR ou imagem

- diminua o conteúdo do QR;
- reduza `sizeDots`;
- use faixas menores em `stripeHeight`;
- confirme que o transporte não foi interrompido durante o envio;
- em buffers pequenos, evite enviar vários trabalhos grandes em sequência sem intervalo.

## Limitações conhecidas

- Bluetooth trabalha com dispositivos previamente pareados; o app não realiza o pareamento.
- Bluetooth Low Energy sem perfil SPP não é suportado.
- A descoberta de rede considera uma sub-rede IPv4 `/24` e a porta 9100.
- Não existe retorno padronizado de papel ausente ou tampa aberta em todos os transportes.
- O comando de corte é ignorado por impressoras sem guilhotina.
- Recursos avançados variam conforme a implementação ESC/POS do fabricante.
- A integração iOS do template não possui os drivers de impressão deste projeto; o foco é Android.
- Impressão em PAX e Gertec depende dos SDKs binários incluídos e deve ser homologada em cada modelo.

## Adicionando um novo transporte

1. Adicione o identificador em `PrinterMethod` e `EffectivePrinterMethod`.
2. Implemente o transporte Android com `connect`, `disconnect`, `isConnected` e `printBytes`.
3. Registre o gerenciador em `PrinterModule.java`.
4. Atualize `methodLabels` e `resolveMethod`, se necessário.
5. Reutilize os bytes criados em `escpos.ts` sempre que o hardware aceitar ESC/POS bruto.
6. Se a API for de alto nível, implemente um interpretador como os drivers PAX e Gertec.
7. Adicione testes e valide em equipamento real.

## Segurança

- Nunca versione tokens, senhas, certificados ou keystores de produção.
- Não confie diretamente em dados recebidos de APIs para montar trabalhos de impressão.
- Limite tamanho de textos, imagens e payloads de QR antes de gerar os bytes.
- Use HTTPS e autenticação adequada ao buscar pedidos em serviços externos.
- Trate dados fiscais e pessoais conforme as regras aplicáveis ao produto.

## Licença e distribuição

O repositório é privado. Antes de redistribuir o aplicativo ou os SDKs binários, confirme os termos de licença de cada fabricante e as permissões da organização responsável pelo projeto.
